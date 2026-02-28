# Ant to Maven Converter

古い Ant プロジェクトの `lib` フォルダなどをスキャンし、JAR の SHA-1 を Maven Central と照合して **pom.xml** を自動生成する GUI ツールです。

**English:** [README.md](README.md)

---

## 機能

- **GUI 操作** … Swing によるウィンドウで、プロジェクトパス選択・実行・ログ確認が可能
- **JAR の自動検出** … 指定ディレクトリ以下を再帰的にスキャンし、`.jar` を収集
- **Maven Central 照合** … 各 JAR の SHA-1 を計算し、[Maven Central Search API](https://search.maven.org/) で groupId / artifactId / version を検索
- **設定ファイル** … Groovy の ConfigSlurper 形式で「除外」「追加」「置換」を柔軟に指定可能
- **最新バージョン検索** … オプションで検出したアーティファクトを最新版にアップグレード
- **ローカル JAR の扱い** … Maven Central で見つからない JAR は `system` スコープで `pom.xml` に出力
- **多言語対応** … 日本語 / English の切り替え（言語プルダウン）
- **JAR パス除外** … 設定で glob パターン（例: `**/test/**`）を指定し、スキャン対象から除外可能

## 必要環境

- **Java 17** 以上
- **Maven 3.x**（ビルド用）

## ビルド

```bash
mvn clean package
```

実行可能 JAR は `target/ant-to-maven-converter-1.0.0.jar` に生成されます（依存関係は shade で同梱）。

## 実行方法

### JAR から実行

```bash
java -jar target/ant-to-maven-converter-1.0.0.jar
```

### IDE から実行

メインクラス `AntToMavenTool` の `main` メソッドを実行してください。

## 使い方

1. 起動すると **「Ant to Maven POM ジェネレーター」** ウィンドウが開きます。
2. **プロジェクトフォルダ** に、変換したい Ant プロジェクトのルート（`lib` など JAR が含まれるディレクトリの親）を指定します。
3. 必要に応じて **設定ファイル** のパスを選択（省略時は `~/.ant-to-maven-converter/ant-to-maven-default.groovy` を使用）。
4. **「依存バージョンを最新に置き換える」** にチェックを入れると、検出した依存関係を最新版に差し替えます。
5. **「POM生成」** をクリックすると、指定ディレクトリ以下がスキャンされ、`pom.xml` が生成されます。
6. 既に `pom.xml` がある場合は「上書きする」「別名で保存」「処理中止」のいずれかを選択できます。

生成された `pom.xml` はプロジェクトルート（指定したパス直下）に出力されます。

## 設定ファイル

設定は **Groovy ConfigSlurper** 形式で記述します。

- **保存場所（デフォルト）**  
  - Windows: `%USERPROFILE%\.ant-to-maven-converter\`  
  - macOS/Linux: `~/.ant-to-maven-converter/`  
  - ファイル名: `ant-to-maven-default.groovy`（初回起動時にサンプルが自動作成されます）

### 主な設定項目

| 項目 | 説明 |
|------|------|
| `excludeDependencies` | pom に出力しない `groupId:artifactId` のリスト |
| `excludeJarPaths` | スキャン対象から除外する JAR のパス（glob 形式。例: `**/test/**`） |
| `addDependencies` | 検出結果の先頭に追加する依存関係のリスト |
| `replaceDependencies` | 検出した `groupId:artifactId` を別の依存関係（1:1 または 1:N）に置き換え |
| `pomProjectTemplate` | 生成する pom の雛形。`{{DEPENDENCIES}}` が依存関係ブロックに置換されます |

リポジトリ内のサンプルは `src/main/resources/ant-to-maven-default.groovy` を参照してください。

## プロジェクト構成

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
        │   └── AntToMavenConverter.groovy   # メインクラス AntToMavenTool
        └── resources/
            ├── ant-to-maven-default.groovy # 設定サンプル
            ├── messages_ja.properties       # 日本語メッセージ
            └── messages_en.properties      # 英語メッセージ
```

## ライセンス

このプロジェクトのライセンスはリポジトリのルートで定義されている場合があります。
