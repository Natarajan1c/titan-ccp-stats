# Titan CCP - Stats

The [Titan Control Center Prototype](http://eprints.uni-kiel.de/43910) (CCP) is a
scalable monitoring infrastructure for [Industrial DevOps](https://industrial-devops.org/).
It allows to monitor, analyze and visualize the electrical power consumption of
devices and machines in production environments such as factories.

This repository contains the **Stats** microservice of the Titan CCP.

## Build and Run

We use Gradle as a build tool. In order to build the executeables run 
`./gradlew build` on Linux/macOS or `./gradlew.bat build` on Windows. This will
create the file `build/distributions/titanccp-stats.tar` which contains
start scripts for Linux/macOS and Windows.

This repository also contains a Dockerfile. Run
`docker build -t titan-ccp-stats .` to create a container from it (after
building it with Gradle).