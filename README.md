# Brooklyn Etcd Entities

Entities for a CoreOS etcd key-value store cluster


### [For contributors] Release Process

#### Snapshot Release

In order to release a new snapshot version to Sonatype:

```bash
mvn source:jar javadoc:jar deploy -DdeployTo=sonatype
```

#### Official Relesae

1. Create a new branch, e.g. `release/2.3.0`, and checkout that branch

2. Update the version running the command below (and double-check that pom.xml was correctly updated):

   ```bash
   GA_VERSION=2.3.0
   ~/repos/brooklyn/brooklyn-dist/release/change-version.sh BROOKLYN_ETCD ${GA_VERSION}-SNAPSHOT ${GA_VERSION}
   ```

3. Confirm it builds: `mvn clean install`

4. Push release to sonatype, following the normal Sonatype process:

   ```bash
   mvn source:jar javadoc:jar deploy -DdeployTo=sonatype
   ```
