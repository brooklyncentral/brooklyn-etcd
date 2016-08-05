# Brooklyn Etcd Entities

Entities for a CoreOS etcd key-value store cluster


### [For contributors] Release Process

#### Snapshot Release

In order to release a new snapshot version to Sonatype:

    mvn source:jar javadoc:jar deploy -DdeployTo=sonatype


#### Official Relesae

1. Create a new branch, e.g. `release/2.3.0`, and checkout that branch

2. Update the version running the command below (and double-check that pom.xml was correctly updated):

    ```
    GA_VERSION=2.3.0
    ~/repos/brooklyn/brooklyn-dist/release/change-version.sh BROOKLYN_ETCD ${GA_VERSION}-SNAPSHOT ${GA_VERSION}
    ```

3. Update the expected download URL in catalog.bom (note the `r=releases` and the correct version), e.g. to

    ```
    https://oss.sonatype.org/service/local/artifact/maven/redirect?r=releases&g=io.brooklyn.etcd&a=brooklyn-etcd&v=2.2.1
    ```

4. Confirm it builds: `mvn clean install`

5. Push release to sonatype, following the normal Sonatype process. See 
   [Very Old Brooklyn Instructions](https://github.com/brooklyncentral/brooklyn/blob/0.7.0-M1/docs/dev/tips/release.md)
   from the days before it was an Apache project, when we were deploying to Sonatype.

    ```
    mvn source:jar javadoc:jar -DdeployTo=sonatype
    ```
