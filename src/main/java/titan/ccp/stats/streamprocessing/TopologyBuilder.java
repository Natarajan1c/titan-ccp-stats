package titan.ccp.stats.streamprocessing;

import com.datastax.driver.core.Session;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import titan.ccp.common.avro.cassandra.AvroDataAdapter;
import titan.ccp.common.cassandra.CassandraWriter;
import titan.ccp.common.cassandra.PredefinedTableNameMappers;
import titan.ccp.model.records.ActivePowerRecord;

/**
 * Builds Kafka Stream Topology for the Stats microservice.
 */
public class TopologyBuilder {

  // private static final Logger LOGGER =
  // LoggerFactory.getLogger(TopologyBuilder.class);

  private final ZoneId zone = ZoneId.of("Europe/Paris"); // TODO as parameter
  private final Serdes serdes;

  private final StreamsBuilder builder = new StreamsBuilder();
  private final KStream<String, ActivePowerRecord> inputStream;
  private final CassandraWriter<SpecificRecord> cassandraWriter;
  private final CassandraKeySelector cassandraKeySelector;

  /**
   * Create a new {@link TopologyBuilder}.
   */
  public TopologyBuilder(
      final Serdes serdes,
      final Session cassandraSession,
      final String activePowerTopic,
      final String aggregatedActivePowerTopic) {

    this.serdes = serdes;

    // 1. Cassandra Writer
    this.cassandraKeySelector = new CassandraKeySelector();
    if (cassandraSession == null) {
      this.cassandraWriter = null; // NOPMD
    } else {
      this.cassandraWriter = CassandraWriter
          .builder(cassandraSession, new AvroDataAdapter())
          .tableNameMapper(PredefinedTableNameMappers.SIMPLE_CLASS_NAME)
          .primaryKeySelectionStrategy(this.cassandraKeySelector)
          .build();
    }

    // 2. Build Streams
    this.inputStream = this.buildInputStream(activePowerTopic, aggregatedActivePowerTopic);
  }

  public Topology build() {
    return this.builder.build();
  }

  private KStream<String, ActivePowerRecord> buildInputStream(final String activePowerTopic,
      final String aggrActivePowerTopic) {
    final KStream<String, ActivePowerRecord> activePowerStream = this.builder
        .stream(
            activePowerTopic,
            Consumed.with(
                this.serdes.string(),
                this.serdes.activePowerRecordValues()));

    final KStream<String, ActivePowerRecord> aggrActivePowerStream = this.builder
        .stream(aggrActivePowerTopic,
            Consumed.with(
                this.serdes.string(),
                this.serdes.aggregatedActivePowerRecordValues()))
        .mapValues(
            aggrAvro -> new ActivePowerRecord(
                aggrAvro.getIdentifier(),
                aggrAvro.getTimestamp(),
                aggrAvro.getSumInW()));

    return activePowerStream.merge(aggrActivePowerStream);
  }

  /**
   * Add a new statistics calculation step.
   */
  public <K, R extends SpecificRecord> void addStat(
      final StatsKeyFactory<K> keyFactory,
      final Serde<K> keySerde,
      final StatsRecordFactory<K, R> statsRecordFactory,
      final RecordDatabaseAdapter<R> recordDatabaseAdapter,
      final TimeWindows timeWindows,
      final String statsTopic) {

    final var statStream = this.addStatCalculation(keyFactory, keySerde, timeWindows);
    this.maybeAddStatStorage(
        statStream,
        keyFactory,
        statsRecordFactory,
        recordDatabaseAdapter);
    this.addStatExpose(
        statStream,
        keyFactory,
        statsRecordFactory,
        timeWindows,
        statsTopic);
  }

  private <K> KStream<Windowed<K>, SummaryStatistics> addStatCalculation(
      final StatsKeyFactory<K> keyFactory,
      final Serde<K> keySerde,
      final TimeWindows timeWindows) {

    return this.inputStream
        .selectKey((key, value) -> {
          final Instant instant = Instant.ofEpochMilli(value.getTimestamp());
          final LocalDateTime dateTime = LocalDateTime.ofInstant(instant, this.zone);
          return keyFactory.createKey(value.getIdentifier(), dateTime);
        })
        .groupByKey(Grouped.with(keySerde, this.serdes.activePowerRecordValues()))
        .windowedBy(timeWindows)
        .aggregate(
            SummaryStatistics::new,
            (k, record, stats) -> stats.add(record),
            Materialized.with(keySerde, this.serdes.summaryStatistics()))
        .toStream();
  }

  private <K, R extends SpecificRecord> void addStatExpose(
      final KStream<Windowed<K>, SummaryStatistics> recordStream,
      final StatsKeyFactory<K> keyFactory,
      final StatsRecordFactory<K, R> statsRecordFactory,
      final TimeWindows timeWindows,
      final String statsTopic) {
    recordStream
        // Only forward updates to the most complete window, i.e. the earliest
        .filter((k, v) -> v.getTimestamp() >= k.window().end() - timeWindows.advanceMs)
        .map((key, value) -> KeyValue.pair(
            keyFactory.getSensorId(key.key()),
            statsRecordFactory.create(key, value.getStats())))
        .to(
            statsTopic,
            Produced.with(
                this.serdes.string(),
                this.serdes.avroValues()));
  }

  private <K, R extends SpecificRecord> void maybeAddStatStorage(
      final KStream<Windowed<K>, SummaryStatistics> recordStream,
      final StatsKeyFactory<K> keyFactory,
      final StatsRecordFactory<K, R> statsRecordFactory,
      final RecordDatabaseAdapter<R> recordDatabaseAdapter) {
    if (this.cassandraWriter == null) {
      return;
    }

    this.cassandraKeySelector.addRecordDatabaseAdapter(recordDatabaseAdapter);

    recordStream
        .map((key, value) -> KeyValue.pair(
            keyFactory.getSensorId(key.key()),
            statsRecordFactory.create(key, value.getStats())))
        // .peek((k, v) -> LOGGER.info("{}: {}", k, v)) // TODO Temp logging
        .foreach((k, record) -> this.cassandraWriter.write(record));

  }

}
