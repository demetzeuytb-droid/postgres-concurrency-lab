# Prerequisites

Inspected on 6 October 2026.

## Verified environment

| Component | Inspection result |
|---|---|
| Repository | `C:\Users\demet\IdeaProjects\postgres-concurrency-lab\postgres-concurrency-lab` |
| Java | Oracle JDK 26, build 26+35-2893 |
| JDK directory | `C:\Program Files\Java\jdk-26` |
| Maven | IntelliJ bundled Apache Maven 3.9.11; reports Java 26 |
| Maven command | `C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd` |
| IntelliJ project | Maven project registered; SDK name `openjdk-26` in project metadata |
| POM | Existing artifact `io.github.demetzeu-droid:postgres-concurrency-lab:1.0-SNAPSHOT`, source/target 26 |
| Application | No Java files or JDBC dependency yet |
| PostgreSQL | Installed at `C:\Program Files\PostgreSQL\18`; Windows service `postgresql-x64-18` running; localhost:5432 accepting connections |
| CLI availability | `mvn`, `psql` and `docker` were not found on PATH |
| JDBC cache | No driver found in the usual local Maven `org/postgresql/postgresql` directory |

