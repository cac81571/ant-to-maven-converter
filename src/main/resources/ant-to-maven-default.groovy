// Ant to Maven Converter - pom.xml 生成用設定 (ConfigSlurper)
// 空欄の項目はデフォルト値（またはプロジェクトフォルダ名）が使われます。

// --- pom.xml の project 部分をテンプレートで定義（pomProjectTemplate が空でないとき使用）---
// 【XML 表記】{{DEPENDENCIES}} のみ検出した依存関係の <dependencies>...</dependencies> に置換されます。
pomProjectTemplate = '''
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example.migration</groupId>
  <artifactId>my-app</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>war</packaging>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
  </properties>
  {{DEPENDENCIES}}
  <build>
    <finalName>${project.artifactId}</finalName>
  </build>
</project>
'''

// --- JAR スキャン時のパス除外（glob 形式）---
// 以下のパターンに一致するパスにある JAR はスキャン対象に含めません（例: test フォルダ配下を除外）
// excludeJarPaths = [
//     '**/test/**',
//     '**/tests/**'
// ]

// --- dependency の除外・追加・置換（必要に応じてコメントを外して編集）---

// 除外: 検出した dependency の groupId:artifactId を指定。pom に出力しません。
// excludeDependencies = [
//     'com.example.legacy:some-jar',
//     'org.old:old-library'
// ]

// 追加: pom に挿入する dependency。検出結果の先頭に追加されます。
// addDependencies = [
//     [groupId: 'org.springframework', artifactId: 'spring-core', version: '5.3.0', scope: 'compile'],
//     [groupId: 'javax.servlet', artifactId: 'javax.servlet-api', version: '4.0.1', scope: 'provided']
// ]

// 置換: 検出した dependency を別のライブラリに差し替え（1:1 または 1:N）。from は検出時の groupId:artifactId
// ※ ConfigSlurper ではキーにコロンを含む Map が正しく解釈されないため、リスト形式（from / to）を使用してください。
// 1:1 の例:
// replaceDependencies = [
//     [ from: 'org.hibernate:hibernate-annotations', to: [groupId: 'org.hibernate', artifactId: 'hibernate-core', version: '5.4.0.Final', scope: 'compile'] ]
// ]
// 1:N の例（1つを複数に置換）:
// replaceDependencies = [
//     [ from: 'org.old:fat-lib', to: [
//         [groupId: 'org.new', artifactId: 'new-a', version: '1.0', scope: 'compile'],
//         [groupId: 'org.new', artifactId: 'new-b', version: '1.0', scope: 'compile']
//     ]]
// ]

// 依存関係の最新化を除外（「依存バージョンを最新に置き換える」がオンのとき、ここに指定した依存は最新検索しない）
// 文字列のみ: 最新化をスキップし、検出時のバージョンのままにする
// Map で version を指定: 最新化をスキップし、指定したバージョンに置換する
// excludeFromVersionUpgrade = [
//     'org.example:legacy-lib',                    // スキップのみ（バージョンはそのまま）
//     [key: 'org.example:another-lib', version: '2.1.0']  // 2.1.0 に固定
// ]
