/**
 * Ant to Maven Converter Tool
 *
 * 古いAntプロジェクトのlibフォルダをスキャンし、Maven Centralと照合してpom.xmlを生成するツール。
 *
 * 機能:
 * - GUIによる操作 (Swing)
 * - JARのSHA-1計算とMaven Central APIによる検索
 * - ConfigSlurperによる柔軟な設定 (除外、置換、追加)
 * - 最新バージョン検索オプション
 * - ローカルJAR (System Scope) のフォールバック処理
 */

import groovy.json.JsonSlurper
import groovy.swing.SwingBuilder
import groovy.xml.MarkupBuilder
import groovy.xml.XmlSlurper
import groovy.xml.XmlParser
import groovy.xml.XmlUtil
import javax.swing.*
import java.awt.*
import java.util.List
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import java.net.URLEncoder
import java.nio.file.FileSystems
import java.nio.file.Path
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

class AntToMavenTool {

    // --- 定数 ---
    private static final String MAVEN_SEARCH_API = "https://search.maven.org/solrsearch/select"
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.ant-to-maven-converter/"
    private static final String CONFIG_FILE = "ant-to-maven-default.groovy"
    private static final String PREF_NODE = "com.example.tools.ant2maven"
    private static final String PREF_KEY_HISTORY = "pathHistory"
    private static final String PREF_KEY_CONFIG_HISTORY = "configPathHistory"
    private static final String PREF_KEY_LANG = "language"
    private static final String[] LANG_CODES = ["ja", "en"]

    // --- UI コンポーネント ---
    private JFrame mainFrame
    private JTextArea logArea
    private JComboBox<String> pathCombo
    private JComboBox<String> configPathCombo
    private JComboBox<String> langCombo
    private JLabel langLabel
    private JLabel projectRootLabel
    private JLabel configFileLabel
    private JButton showFolderProjectBtn
    private JButton showFolderConfigBtn
    private JButton exportCsvBtn
    private JButton importCsvBtn
    private JCheckBox latestVersionCheck
    private JButton runButton
    private JButton stopButton
    private JLabel statusLabel
    private JProgressBar progressBar

    // --- 状態管理 ---
    private AtomicBoolean isRunning = new AtomicBoolean(false)
    private ConfigObject config
    private Preferences prefs = Preferences.userRoot().node(PREF_NODE)
    private JsonSlurper jsonSlurper = new JsonSlurper()
    private Properties i18nMessages = new Properties()

    static void main(String[] args) {
        new AntToMavenTool().run()
    }

    void run() {
        // デフォルト言語は en。保存値が無い場合は OS が ja のときのみ ja、それ以外は en
        String savedLang = prefs.get(PREF_KEY_LANG, null)
        if (savedLang == null || savedLang.isEmpty()) {
            savedLang = ("ja" == Locale.getDefault().language) ? "ja" : "en"
        }
        loadI18n(savedLang)
        setupConfig()
        setupUI()
    }

    /** 指定ロケールのメッセージプロパティを読み込む（UTF-8） */
    private void loadI18n(String lang) {
        if (lang == null || lang.isEmpty()) lang = "ja"
        String resource = ("en" == lang) ? "/messages_en.properties" : "/messages_ja.properties"
        def stream = getClass().getResourceAsStream(resource)
        if (stream == null) stream = getClass().getResourceAsStream("/messages_ja.properties")
        if (stream != null) {
            try {
                i18nMessages.clear()
                i18nMessages.load(new InputStreamReader(stream, "UTF-8"))
            } finally {
                stream.close()
            }
        }
    }

    /** 言語切り替え時にUIの表示文言を再適用する */
    private void refreshUIStrings() {
        if (mainFrame != null) mainFrame.title = i18n('app.title')
        if (langLabel != null) langLabel.text = i18n('ui.language')
        if (projectRootLabel != null) projectRootLabel.text = i18n('ui.projectRootPath')
        if (configFileLabel != null) configFileLabel.text = i18n('ui.configFile')
        if (showFolderProjectBtn != null) showFolderProjectBtn.text = i18n('ui.showFolder')
        if (showFolderConfigBtn != null) showFolderConfigBtn.text = i18n('ui.showFolder')
        if (latestVersionCheck != null) latestVersionCheck.text = i18n('ui.latestVersionReplace')
        if (runButton != null) runButton.text = i18n('ui.generatePom')
        if (stopButton != null) stopButton.text = i18n('ui.stop')
        if (progressBar != null) progressBar.string = i18n('ui.ready')
        if (exportCsvBtn != null) exportCsvBtn.text = i18n('ui.exportCsv')
        if (importCsvBtn != null) importCsvBtn.text = i18n('ui.importCsv')
    }

    private String i18n(String key) {
        return i18nMessages.getProperty(key, key)
    }

    private String i18n(String key, Object... args) {
        String template = i18nMessages.getProperty(key, key)
        return (args == null || args.length == 0) ? template : MessageFormat.format(template, args)
    }

    /**
     * 設定ファイルの読み込み（存在しない場合は JAR 同梱の ant-to-maven-default.groovy をコピーして作成）
     */
    private void setupConfig() {
        File configDir = new File(CONFIG_DIR)
        if (!configDir.exists()) configDir.mkdirs()

        File configFile = new File(configDir, CONFIG_FILE)
        if (!configFile.exists()) {
            def resourceUrl = getClass().getResource("/ant-to-maven-default.groovy")
            if (resourceUrl != null) {
                println "同梱リソースの絶対パス: ${resourceUrl.toString()}"
                InputStream bundled = getClass().getResourceAsStream("/ant-to-maven-default.groovy")
                configFile.text = bundled.getText("UTF-8")
                bundled.close()
            } else {
                // クラスパスに同梱リソースがない場合（IDE実行時など）のフォールバック
                println "ファイルが見つかりません。同梱リソースがクラスパスにありません（IDE実行時など）。"
            }
        }

        try {
            config = new ConfigSlurper().parse(configFile.toURI().toURL())
        } catch (Exception e) {
            System.err.println("設定ファイルの読み込みに失敗しました: " + e.message)
            config = new ConfigObject()
        }
    }

    /**
     * UIの構築
     */
    private void setupUI() {
        // LookAndFeelの設定
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName())
                    break
                }
            }
        } catch (Exception e) {
            // Fallback to default
        }

        // 実行モード判定 (JAR vs IDE)
        boolean isJarExecution = this.class.getResource("AntToMavenTool.class").toString().startsWith("jar:")
        int closeOperation = isJarExecution ? JFrame.EXIT_ON_CLOSE : JFrame.DISPOSE_ON_CLOSE

        // 履歴のロード
        String historyStr = prefs.get(PREF_KEY_HISTORY, "")
        List<String> history = historyStr ? historyStr.split("###").toList() : []
        String savedLang = prefs.get(PREF_KEY_LANG, "en")
        int langIndex = LANG_CODES.findIndexOf { it == savedLang }
        if (langIndex < 0) langIndex = 0

        SwingBuilder swing = new SwingBuilder()
        swing.edt {
            mainFrame = frame(title: i18n('app.title'), size: [800, 600],
                    defaultCloseOperation: closeOperation, locationRelativeTo: null) {
                borderLayout()

                // 上部: 設定エリア
                panel(constraints: BorderLayout.NORTH) {
                    boxLayout(axis: BoxLayout.Y_AXIS)

                    panel(alignmentX: 0f) {
                        flowLayout(alignment: FlowLayout.LEFT)
                        projectRootLabel = label(text: i18n('ui.projectRootPath'))
                        pathCombo = comboBox(editable: true, items: history, preferredSize: [400, 25])
                        showFolderProjectBtn = button(text: i18n('ui.showFolder'), actionPerformed: { openProjectFolder() })
                        langLabel = label(text: i18n('ui.language'))
                        langCombo = comboBox(items: LANG_CODES.collect { i18n("lang.${it}") }, selectedIndex: langIndex, preferredSize: [85, 22],
                                actionPerformed: {
                                    int idx = langCombo.selectedIndex
                                    if (idx >= 0 && idx < LANG_CODES.length) {
                                        String lang = LANG_CODES[idx]
                                        prefs.put(PREF_KEY_LANG, lang)
                                        loadI18n(lang)
                                        refreshUIStrings()
                                    }
                                })
                    }

                    panel(alignmentX: 0f) {
                        flowLayout(alignment: FlowLayout.LEFT)
                        configFileLabel = label(text: i18n('ui.configFile'))
                        def defaultConfigPath = new File(CONFIG_DIR, CONFIG_FILE).absolutePath
                        def configHistoryStr = prefs.get(PREF_KEY_CONFIG_HISTORY, defaultConfigPath)
                        def configHistory = configHistoryStr ? configHistoryStr.split("###") as List : [defaultConfigPath]
                        configPathCombo = comboBox(editable: true, items: configHistory, preferredSize: [450, 25])
                        showFolderConfigBtn = button(text: i18n('ui.showFolder'), actionPerformed: { openConfigFolder() })
                    }

                    panel(alignmentX: 0f) {
                        flowLayout(alignment: FlowLayout.LEFT)
                        latestVersionCheck = checkBox(text: i18n('ui.latestVersionReplace'), selected: true)
                        runButton = button(text: i18n('ui.generatePom'), actionPerformed: { startProcess() })
                        stopButton = button(text: i18n('ui.stop'), enabled: false, actionPerformed: { stopProcess() })
                    }
                }

                // 中央: ログエリア
                scrollPane(constraints: BorderLayout.CENTER) {
                    logArea = textArea(editable: false, font: new Font("Monospaced", Font.PLAIN, 12))
                }

                // 下部: コントロール
                panel(constraints: BorderLayout.SOUTH) {
                    borderLayout()
                    progressBar = progressBar(visible: true, stringPainted: true, string: i18n('ui.ready'))
                    
                    panel(constraints: BorderLayout.EAST) {
                        flowLayout()
                        exportCsvBtn = button(text: i18n('ui.exportCsv'), actionPerformed: { exportDependenciesToCsv() })
                        importCsvBtn = button(text: i18n('ui.importCsv'), actionPerformed: { importDependenciesFromCsv() })
                    }
                    widget(progressBar, constraints: BorderLayout.CENTER)
                }
            }
        }
        mainFrame.visible = true
        log(i18n('log.appStarted', isJarExecution ? "JAR" : "Script/IDE"))
    }

    // --- ロジック ---

    private void startProcess() {
        String path = pathCombo.selectedItem?.toString()
        if (!path) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.selectProjectPath'), i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
            return
        }

        File projectDir = new File(path)
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.invalidDirectory'), i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
            return
        }

        // pom.xml 上書き確認（処理の先頭で実施）
        File outputPomFile = new File(projectDir, "pom.xml")
        if (outputPomFile.exists()) {
            Object[] options = [i18n('overwrite.option'), i18n('overwrite.saveAs'), i18n('overwrite.cancel')]
            int result = JOptionPane.showOptionDialog(mainFrame,
                i18n('overwrite.prompt'),
                i18n('overwrite.title'),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0])
            if (result == 2 || result == JOptionPane.CLOSED_OPTION) return
            if (result == 1) {
                outputPomFile = new File(projectDir, "pom_generated_${System.currentTimeMillis()}.xml")
            }
        }

        // 履歴保存
        saveHistory(path)

        isRunning.set(true)
        runButton.enabled = false
        stopButton.enabled = true
        pathCombo.enabled = false
        logArea.text = ""
        
        // 設定を再読み込み（コンボで選択中のパスから）
        String configPath = configPathCombo?.selectedItem?.toString()?.trim() ?: new File(CONFIG_DIR, CONFIG_FILE).absolutePath
        try {
            File configFile = new File(configPath)
            if (configFile.exists()) {
                config = new ConfigSlurper().parse(configFile.toURI().toURL())
                saveConfigPathHistory(configPath)
                log(i18n('log.configReloaded', configPath))
            }
        } catch (Exception e) {
            log(i18n('log.configLoadWarning', e.message))
        }

        // バックグラウンドスレッドで実行
        final File pomOut = outputPomFile
        Thread.start {
            try {
                processDirectory(projectDir, pomOut)
            } catch (Exception e) {
                log(i18n('msg.error') + ": ${e.message}")
                e.printStackTrace()
            } finally {
                SwingUtilities.invokeLater {
                    isRunning.set(false)
                    runButton.enabled = true
                    stopButton.enabled = false
                    pathCombo.enabled = true
                    progressBar.string = i18n('ui.done')
                    progressBar.value = 100
                }
            }
        }
    }

    private void stopProcess() {
        if (isRunning.get()) {
            isRunning.set(false)
            log(i18n('log.stopRequested'))
        }
    }

    /** 設定ファイルがあるフォルダをエクスプローラで開く */
    private void openConfigFolder() {
        String path = configPathCombo?.selectedItem?.toString()?.trim()
        if (!path) {
            path = new File(CONFIG_DIR, CONFIG_FILE).absolutePath
        }
        File dir = new File(path).parentFile ?: new File(CONFIG_DIR)
        openFolder(dir, i18n('msg.dialogTitle.configFolder'))
    }

    /** 設定ファイルパスを履歴に追加して永続化 */
    private void saveConfigPathHistory(String path) {
        if (!path?.trim()) return
        DefaultComboBoxModel model = (DefaultComboBoxModel) configPathCombo.model
        if (model.getIndexOf(path) == -1) {
            model.addElement(path)
        }
        List<String> items = []
        for (int i = 0; i < model.size; i++) {
            items.add(model.getElementAt(i).toString())
        }
        prefs.put(PREF_KEY_CONFIG_HISTORY, items.join("###"))
    }

    /** 選択中のプロジェクトフォルダをエクスプローラで開く */
    private void openProjectFolder() {
        String path = pathCombo?.selectedItem?.toString()
        if (!path?.trim()) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.selectProjectPath'), i18n('msg.dialogTitle.folder'), JOptionPane.WARNING_MESSAGE)
            return
        }
        File dir = new File(path)
        if (!dir.exists() || !dir.directory) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.invalidDirectory'), i18n('msg.dialogTitle.folder'), JOptionPane.ERROR_MESSAGE)
            return
        }
        openFolder(dir, i18n('msg.dialogTitle.folder'))
    }

    /** フォルダを開く。失敗時はエラーダイアログを表示 */
    private void openFolder(File folder, String dialogTitle = null) {
        if (dialogTitle == null) dialogTitle = i18n('msg.error')
        if (folder == null || !folder.exists() || !folder.directory) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.folderNotFound', folder?.absolutePath ?: ''), dialogTitle, JOptionPane.ERROR_MESSAGE)
            return
        }
        try {
            Desktop.getDesktop().open(folder)
        } catch (Exception e) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.folderOpenFailed', e.message), dialogTitle, JOptionPane.ERROR_MESSAGE)
        }
    }

    private void saveHistory(String path) {
        DefaultComboBoxModel model = (DefaultComboBoxModel) pathCombo.model
        if (model.getIndexOf(path) == -1) {
            model.addElement(path)
        }
        
        List<String> items = []
        for (int i = 0; i < model.size; i++) {
            items.add(model.getElementAt(i).toString())
        }
        prefs.put(PREF_KEY_HISTORY, items.join("###"))
    }

    private void processDirectory(File projectDir, File outputPomFile) {
        log(i18n('log.scanning', projectDir.absolutePath))

        def excludeJarPathPatterns = config.excludeJarPaths instanceof Collection ? config.excludeJarPaths : []
        def pathMatchers = excludeJarPathPatterns.collect { pattern ->
            try {
                FileSystems.default.getPathMatcher("glob:${pattern}")
            } catch (Exception e) {
                log(i18n('log.warnInvalidExcludePattern', pattern, e.message))
                null
            }
        }.findAll { it != null }

        List<File> jars = []
        projectDir.eachFileRecurse {
            if (it.name.endsWith('.jar') && isRunning.get()) {
                Path relativePath = projectDir.toPath().relativize(it.toPath())
                String pathStr = relativePath.toString().replace(File.separator, '/')
                def pathSegments = pathStr.isEmpty() ? [] : pathStr.split('/').toList()
                Path normalizedForGlob = pathSegments.isEmpty()
                    ? relativePath.getFileSystem().getPath("")
                    : FileSystems.default.getPath(pathSegments[0], *pathSegments.drop(1))
                if (pathMatchers.any { it.matches(normalizedForGlob) }) {
                    log(i18n('log.excludedJarPath', pathStr))
                    return
                }
                jars << it
            }
        }

        if (jars.isEmpty()) {
            log(i18n('log.noJarsFound'))
            return
        }

        log(i18n('log.jarsFound', jars.size().toString()))
        
        List<Dependency> scannedDeps = []
        int count = 0
        
        for (File jar : jars) {
            if (!isRunning.get()) break
            
            count++
            updateProgress(count, jars.size(), i18n('log.analyzing', jar.name))
            
            try {
                String sha1 = calculateSha1(jar)
                def artifact = searchMavenCentral(sha1)
                
                if (artifact) {
                    // 日付形式バージョンの警告チェック (例: 20040616)
                    if (artifact.v ==~ /^\d{8}$/) {
                        log(i18n('log.warnDateVersion', artifact.g, artifact.a, artifact.v))
                    }
                    
                    log(i18n('log.found', jar.name, artifact.g, artifact.a, artifact.v))
                    
                    scannedDeps << new Dependency(
                        groupId: artifact.g,
                        artifactId: artifact.a,
                        version: artifact.v,
                        scope: 'compile',
                        originalFile: jar
                    )
                } else {
                    log(i18n('log.notFound', jar.name))
                    // プロジェクトルートからの相対パスを計算
                    String relativePath = getRelativePath(projectDir, jar)
                    
                    scannedDeps << new Dependency(
                        groupId: 'local.dependency',
                        artifactId: jar.name.replace('.jar', ''),
                        version: '1.0.0',
                        scope: 'system',
                        systemPath: "\${project.basedir}/${relativePath}",
                        originalFile: jar,
                        dependencyComment: i18n('comment.systemScope')
                    )
                }
            } catch (Exception e) {
                log(i18n('log.errorProcessing', jar.name, e.message))
            }
            
            // APIレートリミット対策
            Thread.sleep(200) 
        }

        if (isRunning.get()) {
            generatePom(projectDir, scannedDeps, outputPomFile)
        }
    }

    private void generatePom(File projectDir, List<Dependency> scannedDependencies, File outputPomFile) {
        updateProgress(100, 100, i18n('log.generatingPom'))
        log("\n" + i18n('log.applyRules'))

        List<Dependency> finalDependencies = []
        Set<String> processedKeys = new HashSet<>()
        List<String> excludedKeys = []

        // ConfigObjectを安全なList/Map型にキャスト
        def excludes = config.excludeDependencies instanceof Collection ? config.excludeDependencies : []
        def additions = config.addDependencies instanceof Collection ? config.addDependencies : []
        def replacements = config.replaceDependencies instanceof Map ? config.replaceDependencies : [:]

        println "excludes: ${excludes}"

        // 1. 除外と置換の適用 (scannedDependenciesを使用)
        scannedDependencies.each { dep ->
            String key = "${dep.groupId}:${dep.artifactId}"
            
            // Exclude check
            if (excludes.contains(key)) {
                log(i18n('log.excluded', key))
                excludedKeys << key
                return
            }

            // Replace check（値は List<String> または Map(from/to) のいずれか）
            if (replacements.containsKey(key)) {
                def replacementVal = replacements[key]
                def toList = (replacementVal instanceof Map && replacementVal.to != null)
                    ? (replacementVal.to instanceof Collection ? replacementVal.to : [replacementVal.to])
                    : (replacementVal instanceof Collection ? replacementVal : [replacementVal])
                log(i18n('log.replaced', key, toList.toString()))
                toList.each { r ->
                    String g, a, v
                    if (r instanceof String) {
                        def parts = r.split(':')
                        if (parts.length >= 3) { g = parts[0]; a = parts[1]; v = parts[2] }
                    } else if (r instanceof Map) {
                        g = r.groupId ?: r.get('groupId')?.toString()
                        a = r.artifactId ?: r.get('artifactId')?.toString()
                        v = r.version ?: r.get('version')?.toString()
                    }
                    if (g && a && v) {
                        String newKey = "${g}:${a}"
                        if (!processedKeys.contains(newKey)) {
                            finalDependencies << new Dependency(
                                groupId: g,
                                artifactId: a,
                                version: v,
                                dependencyComment: i18n('comment.replace', key, g, a, v)
                            )
                            processedKeys.add(newKey)
                        }
                    }
                }
                return
            }

            // Normal add
            if (!processedKeys.contains(key)) {
                finalDependencies << dep
                processedKeys.add(key)
            }
        }

        // 2. 追加設定の適用（要素は String "g:a:v" または Map(groupId, artifactId, version)）
        additions.each { add ->
            String g, a, v, s, c
            if (add instanceof String) {
                def parts = add.split(':')
                if (parts.length >= 3) { g = parts[0]; a = parts[1]; v = parts[2] }
            } else if (add instanceof Map) {
                g = add.groupId ?: add.get('groupId')?.toString()
                a = add.artifactId ?: add.get('artifactId')?.toString()
                v = add.version ?: add.get('version')?.toString()
                s = add.scope ?: add.get('scope')?.toString()
                c = add.classifier ?: add.get('classifier')?.toString()
            }
            if (g && a && v) {
                String key = "${g}:${a}"
                if (!processedKeys.contains(key)) {
                    log(i18n('log.added', g, a, v))
                    finalDependencies << new Dependency(
                        groupId: g,
                        artifactId: a,
                        version: v,
                        scope: s,
                        classifier: c,
                        dependencyComment: i18n('comment.add', g, a, v)
                    )
                    processedKeys.add(key)
                }
            }
        }

        // 3. バージョンアップの適用（「依存バージョンを最新に置き換える」がオンのとき、追加・除外・置換後の一覧に対して実施）
        if (latestVersionCheck?.selected) {
            log("\n" + i18n('log.versionUpgrade'))
            finalDependencies.each { dep ->
                if (!isRunning.get()) return
                if (dep.scope == 'system' || dep.systemPath) return
                String latest = getLatestVersion(dep.groupId, dep.artifactId)
                if (latest && latest != dep.version) {
                    log(i18n('log.versionUpgraded', dep.groupId, dep.artifactId, dep.version, latest))
                    dep.versionComment = i18n('comment.versionUpgrade', dep.version, latest)
                    dep.version = latest
                }
                Thread.sleep(200)
            }
        }

        // POM出力（上書き確認は startProcess 先頭で実施済み）
        def writer = new StringWriter()

        // ConfigSlurper may return ConfigObject; only call trim() on CharSequence (String/GString)
        def templateRaw = config?.pomProjectTemplate
        def templateStr = (templateRaw instanceof CharSequence) ? templateRaw.toString().trim() : null
        if (templateStr) {
            // テンプレートの {{DEPENDENCIES}} を <dependencies> ブロックに差し替え
            def depsWriter = new StringWriter()
            buildDependenciesSection(new MarkupBuilder(depsWriter), finalDependencies, excludedKeys)
            def out = templateStr.replace('{{DEPENDENCIES}}', depsWriter.toString())
            writer.write(out)
        } else {
            // テンプレートが無い場合は標準の project 構造を生成
            MarkupBuilder xml = new MarkupBuilder(writer)

            xml.project(xmlns: 'http://maven.apache.org/POM/4.0.0', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance',
                    'xsi:schemaLocation': 'http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd') {
                modelVersion('4.0.0')
                groupId(config.project.groupId ?: 'com.example')
                artifactId(config.project.artifactId ?: projectDir.name)
                version(config.project.version ?: '1.0.0-SNAPSHOT')
                
                properties {
                    'project.build.sourceEncoding'('UTF-8')
                    'maven.compiler.source'(config.project.javaVersion ?: '1.8')
                    'maven.compiler.target'(config.project.javaVersion ?: '1.8')
                }

                // 依存関係タグの生成
                buildDependenciesSection(xml, finalDependencies, excludedKeys)
                
                build {
                    plugins {
                        plugin {
                            groupId('org.apache.maven.plugins')
                            artifactId('maven-compiler-plugin')
                            version('3.11.0')
                        }
                    }
                }
            }
        }

        outputPomFile.text = writer.toString()
        log("\n" + i18n('log.pomSuccess', outputPomFile.name))
        JOptionPane.showMessageDialog(mainFrame, i18n('msg.pomComplete', outputPomFile.absolutePath))
    }

    /** 選択中のプロジェクトフォルダと pom.xml を返す。無効なら null */
    private File getProjectPomFile() {
        String path = pathCombo?.selectedItem?.toString()?.trim()
        if (!path) return null
        File projectDir = new File(path)
        if (!projectDir.exists() || !projectDir.directory) return null
        File pom = new File(projectDir, "pom.xml")
        pom.exists() ? pom : null
    }

    /** pom.xml から依存関係一覧をパースして返す */
    private List<Dependency> parseDependenciesFromPom(File pomFile) {
        List<Dependency> list = []
        def root = new XmlSlurper().parse(pomFile)
        root.depthFirst().findAll { it.name() == 'dependency' }.each { d ->
            def g = d.children().find { it.name() == 'groupId' }?.text()?.trim()
            def a = d.children().find { it.name() == 'artifactId' }?.text()?.trim()
            def v = d.children().find { it.name() == 'version' }?.text()?.trim()
            if (g && a && v) {
                def scope = d.children().find { it.name() == 'scope' }?.text()?.trim() ?: 'compile'
                list << new Dependency(groupId: g, artifactId: a, version: v, scope: scope)
            }
        }
        return list
    }

    /** プロジェクトフォルダの pom.xml の依存関係をCSVでエクスポート */
    private void exportDependenciesToCsv() {
        File pomFile = getProjectPomFile()
        if (!pomFile) {
            JOptionPane.showMessageDialog(mainFrame,
                i18n('msg.export.needPom'),
                i18n('ui.exportCsv'), JOptionPane.WARNING_MESSAGE)
            return
        }
        try {
            List<Dependency> toExport = parseDependenciesFromPom(pomFile)
            if (toExport.isEmpty()) {
                JOptionPane.showMessageDialog(mainFrame, i18n('msg.export.noDeps'), i18n('ui.exportCsv'), JOptionPane.INFORMATION_MESSAGE)
                return
            }
            def fc = new JFileChooser()
            fc.dialogTitle = i18n('msg.export.saveTitle')
            fc.currentDirectory = pomFile.parentFile
            fc.selectedFile = new File(pomFile.parentFile, "dependencies.csv")
            if (fc.showSaveDialog(mainFrame) != JFileChooser.APPROVE_OPTION) return
            File f = fc.selectedFile
            f.withWriter('UTF-8') { w ->
                w.writeLine("groupId,artifactId,version,scope")
                toExport.sort { it.groupId }.each { dep ->
                    w.writeLine("${dep.groupId},${dep.artifactId},${dep.version},${dep.scope ?: 'compile'}")
                }
            }
            log(i18n('log.exported', f.absolutePath, toExport.size().toString()))
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.export.done', toExport.size().toString(), f.absolutePath), i18n('ui.exportCsv'), JOptionPane.INFORMATION_MESSAGE)
        } catch (Exception e) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.export.failed', e.message), i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
        }
    }

    /** CSVから依存関係を読み込み、既存を削除してから全件を pom.xml にインポートする */
    private void importDependenciesFromCsv() {
        File pomFile = getProjectPomFile()
        if (!pomFile) {
            JOptionPane.showMessageDialog(mainFrame,
                i18n('msg.import.needPom'),
                i18n('ui.importCsv'), JOptionPane.WARNING_MESSAGE)
            return
        }
        def fc = new JFileChooser()
        fc.dialogTitle = i18n('msg.import.selectTitle')
        fc.currentDirectory = pomFile.parentFile
        if (fc.showOpenDialog(mainFrame) != JFileChooser.APPROVE_OPTION) return
        File csvFile = fc.selectedFile
        if (!csvFile.exists() || !csvFile.canRead()) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.import.fileUnreadable', csvFile.absolutePath), i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
            return
        }
        try {
            List<Dependency> csvDeps = []
            def lines = csvFile.readLines('UTF-8')
            if (lines.isEmpty()) {
                JOptionPane.showMessageDialog(mainFrame, i18n('msg.import.noData'), i18n('ui.importCsv'), JOptionPane.WARNING_MESSAGE)
                return
            }
            boolean hasHeader = lines[0].toLowerCase().contains('groupid') || lines[0].toLowerCase().contains('groupId')
            int start = hasHeader ? 1 : 0
            for (int i = start; i < lines.size(); i++) {
                def line = lines[i].trim()
                if (!line) continue
                def cols = line.split(',', -1).collect { it.trim() }
                if (cols.size() >= 3 && cols[0] && cols[1] && cols[2]) {
                    csvDeps << new Dependency(
                        groupId: cols[0],
                        artifactId: cols[1],
                        version: cols[2],
                        scope: (cols.size() >= 4 && cols[3]) ? cols[3] : 'compile'
                    )
                }
            }
            if (csvDeps.isEmpty()) {
                JOptionPane.showMessageDialog(mainFrame, i18n('msg.import.noValidDeps'), i18n('ui.importCsv'), JOptionPane.WARNING_MESSAGE)
                return
            }
            // pom.xml をパース（編集用に XmlParser、名前空間なしで扱う）
            def parser = new XmlParser(false, false)
            parser.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
            def pom = parser.parse(pomFile)
            // pom.dependencies は NodeList を返すため、先頭要素を取得する
            def depList = pom.dependencies
            def dependenciesNode = (depList && depList.size() > 0) ? depList[0] : pom.depthFirst().find { it.name().toString().endsWith('dependencies') }
            if (!dependenciesNode) {
                dependenciesNode = new Node(pom, 'dependencies', [:], [])
                pom.append(dependenciesNode)
            }
            // 既存の dependency をすべて削除
            def toRemove = dependenciesNode.depthFirst().findAll { it.name().toString().endsWith('dependency') }
            toRemove.each { it.parent().remove(it) }
            // CSV の全件を追加
            csvDeps.each { dep ->
                def depNode = new Node(dependenciesNode, 'dependency', [:], [])
                new Node(depNode, 'groupId', [:], dep.groupId)
                new Node(depNode, 'artifactId', [:], dep.artifactId)
                new Node(depNode, 'version', [:], dep.version)
                if (dep.scope && dep.scope != 'compile') {
                    new Node(depNode, 'scope', [:], dep.scope)
                }
            }
            // XmlUtil.serialize でできる余計な空白行を除去（連続改行を1つに）
            def serialized = XmlUtil.serialize(pom).replaceAll(/\n\s*\n/, '\n')
            pomFile.text = serialized
            log(i18n('log.imported', pomFile.absolutePath, csvDeps.size().toString()))
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.import.done', csvDeps.size().toString(), pomFile.absolutePath), i18n('ui.importCsv'), JOptionPane.INFORMATION_MESSAGE)
        } catch (Exception e) {
            println i18n('msg.import.failed', e.message)
            e.printStackTrace()
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.import.failed', e.message), i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
        }
    }

    // --- ヘルパーメソッド ---
    /** MarkupBuilder に <dependencies> ブロックを書き出す。除外・追加・置換のコメントを付与 */
    private void buildDependenciesSection(MarkupBuilder builder, List<Dependency> finalDependencies, List<String> excludedKeys = []) {
        builder.dependencies {
            if (excludedKeys) {
                excludedKeys.each { key -> mkp.yieldUnescaped('\n  <!-- ' + i18n('comment.excluded', key) + ' -->') }
            }
            finalDependencies.sort { it.groupId }.each { dep ->
                if (dep.dependencyComment) {
                    mkp.yieldUnescaped('\n    ')
                    mkp.comment(dep.dependencyComment)
                }
                dependency {
                    groupId(dep.groupId)
                    artifactId(dep.artifactId)
                    version(dep.version)
                    if (dep.versionComment) {
                        mkp.comment(dep.versionComment)
                    }
                    if (dep.scope && dep.scope != 'compile') {
                        scope(dep.scope)
                    }
                    if (dep.systemPath) {
                        systemPath(dep.systemPath)
                    }
                    if (dep.classifier) {
                        classifier(dep.classifier)
                    }
                }
            }
        }
    }

    private String calculateSha1(File file) {
        MessageDigest digest = MessageDigest.getInstance("SHA-1")
        file.eachByte(4096) { buffer, length ->
            digest.update(buffer, 0, length)
        }
        return digest.digest().encodeHex().toString()
    }

    private def searchMavenCentral(String sha1) {
        try {
            // クエリパラメータをURLエンコード
            String query = "1:\"${sha1}\""
            String encodedQuery = URLEncoder.encode(query, "UTF-8")
            String url = "${MAVEN_SEARCH_API}?q=${encodedQuery}&rows=1&wt=json"
            
            String jsonText = new URL(url).getText([connectTimeout: 5000, readTimeout: 5000])
            def json = jsonSlurper.parseText(jsonText)
            
            if (json.response.numFound > 0) {
                def doc = json.response.docs[0]
                return [g: doc.g, a: doc.a, v: doc.v]
            }
        } catch (Exception e) {
            log(i18n('log.apiError', sha1, e.message))
        }
        return null
    }

    private String getLatestVersion(String groupId, String artifactId) {
        try {
            String q = "g:\"${groupId}\" AND a:\"${artifactId}\""
            String url = "${MAVEN_SEARCH_API}?q=${URLEncoder.encode(q, "UTF-8")}&core=gav&rows=1&wt=json&sort=v+desc"
            
            String jsonText = new URL(url).getText([connectTimeout: 5000, readTimeout: 5000])
            def json = jsonSlurper.parseText(jsonText)
            
            if (json.response.numFound > 0) {
                 def doc = json.response.docs[0]
                 if (doc.latestVersion) return doc.latestVersion
                 return doc.v
            }
        } catch (Exception e) {
             // ignore
        }
        return null
    }

    private String getRelativePath(File base, File file) {
        return base.toURI().relativize(file.toURI()).getPath()
    }

    private void updateProgress(int current, int max, String message) {
        SwingUtilities.invokeLater {
            progressBar.maximum = max
            progressBar.value = current
            progressBar.string = message
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater {
            logArea.append(message + "\n")
            logArea.setCaretPosition(logArea.document.length)
        }
    }

    // --- 内部クラス ---
    
    static class Dependency {
        String groupId
        String artifactId
        String version
        String scope = "compile"
        String classifier
        String systemPath
        File originalFile
        String dependencyComment
        String versionComment

        String toString() { "${groupId}:${artifactId}:${version}" }
    }
}