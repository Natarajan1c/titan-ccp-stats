# Titan CCP - History

The [Titan Control Center Prototype](http://eprints.uni-kiel.de/43910) (CCP) is a
scalable monitoring infrastructure for [Industrial DevOps](https://industrial-devops.org/).
It allows to monitor, analyze and visualize the electrical power consumption of
devices and machines in production environments such as factories.

This repository contains the **History** microservice of the Titan CCP.
*Note: Formerly, this service was named Aggregation. Not all internal parts are completely renamed yet.*

## Build and Run

To simply run the source code from within, e.g., Eclipse make sure to add
`--add-modules=jdk.incubator.httpclient` as a VM argument. Otherwise you get a
`NoClassDefFoundError` exception.

We use Gradle as a build tool. In order to build the executeables run 
`./gradlew build` on Linux/macOS or `./gradlew.bat build` on Windows. This will
create the file `build/distributions/titanccp-history.tar` which contains
start scripts for Linux/macOS and Windows.

This repository also contains a Dockerfile. Run
`docker build -t titan-ccp-aggregation .` to create a container from it (after
building it with Gradle).