# Ant to Maven Converter

A GUI tool that scans legacy Ant project folders (e.g. `lib`), computes JAR SHA-1 hashes, looks them up on Maven Central, and **generates pom.xml** automatically.

**日本語:** [README_ja.md](README_ja.md)

---

## Features

- **GUI** … Swing window for project path selection, execution, and log viewing
- **JAR discovery** … Recursively scans the chosen directory and collects `.jar` files
- **Maven Central lookup** … Computes SHA-1 for each JAR and queries [Maven Central Search API](https://search.maven.org/) for groupId / artifactId / version
- **Config file** … Groovy ConfigSlurper format for flexible “exclude”, “add”, and “replace” rules
- **Latest version** … Optional upgrade of detected artifacts to their latest versions
- **Local JARs** … JARs not found on Maven Central are written to `pom.xml` with `system` scope
- **i18n** … UI language switch: 日本語 / English (dropdown)
- **JAR path exclusion** … Glob patterns in config (e.g. `**/test/**`) to exclude paths from scanning

## Requirements

- **Java 17** or later
- **Maven 3.x** (for building)

## Build

```bash
mvn clean package
```

The runnable JAR is produced at `target/ant-to-maven-converter-1.0.0.jar` (dependencies shaded).

## Run

### From JAR

```bash
java -jar target/ant-to-maven-converter-1.0.0.jar
```

### From IDE

Run the `main` method of the `AntToMavenTool` class.

## Usage

1. On startup the **“Ant to Maven POM Generator”** window opens.
2. Set **Project folder** to the root of the Ant project to convert (parent of directories that contain JARs, e.g. `lib`).
3. Optionally choose a **config file** path (default: `~/.ant-to-maven-converter/ant-to-maven-default.groovy`).
4. Check **“Replace dependency versions with latest”** to upgrade detected dependencies to their latest versions.
5. Click **“Generate POM”** to scan the directory and generate `pom.xml`.
6. If `pom.xml` already exists, you can choose Overwrite, Save as, or Cancel.

The generated `pom.xml` is written under the project root (the path you specified).

## Config file

Configuration is in **Groovy ConfigSlurper** format.

- **Default location**  
  - Windows: `%USERPROFILE%\.ant-to-maven-converter\`  
  - macOS/Linux: `~/.ant-to-maven-converter/`  
  - File: `ant-to-maven-default.groovy` (a sample is created on first run)

### Main options

| Option | Description |
|--------|-------------|
| `excludeDependencies` | List of `groupId:artifactId` to omit from the generated pom |
| `excludeJarPaths` | Glob patterns for JAR paths to exclude from scanning (e.g. `**/test/**`) |
| `addDependencies` | Dependencies to add at the top of the result |
| `replaceDependencies` | Replace detected `groupId:artifactId` with other dependency(ies) (1:1 or 1:N) |
| `pomProjectTemplate` | Template for the generated pom; `{{DEPENDENCIES}}` is replaced by the dependencies block |

See the sample in the repo: `src/main/resources/ant-to-maven-default.groovy`.

## Project layout

```
ant-to-maven-converter/
├── pom.xml
├── README.md
├── README_ja.md
├── docs/
│   └── METHODS.md
└── src/
    └── main/
        ├── groovy/
        │   └── AntToMavenConverter.groovy   # Main class AntToMavenTool
        └── resources/
            ├── ant-to-maven-default.groovy # Config sample
            ├── messages_ja.properties       # Japanese messages
            └── messages_en.properties      # English messages
```

## License

The project license is defined at the repository root, if applicable.
