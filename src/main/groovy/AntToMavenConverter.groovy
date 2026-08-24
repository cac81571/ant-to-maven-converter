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

import groovy.json.JsonOutput
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
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import groovy.util.ConfigObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.apache.maven.artifact.versioning.ComparableVersion
import com.formdev.flatlaf.FlatLightLaf

class AntToMavenTool {

    // --- 定数 ---
    /** Maven Central Search API（search.maven.org から移行） */
    private static final String MAVEN_SEARCH_API = "https://central.sonatype.com/solrsearch/select"
    /**
     * SHA-1 完全一致検索。central.sonatype.com の 1: は引用符付きで 0 件、引用符なしだと別バージョンまでヒットするため
     * レガシー Solr（search.maven.org）を使う。
     */
    private static final String MAVEN_SHA1_SEARCH_API = "https://search.maven.org/solrsearch/select"
    /** Maven Central リポジトリベースURL（maven-metadata.xml 取得用。REST API より最新情報の反映が早い） */
    private static final String MAVEN_REPO_BASE = "https://repo1.maven.org/maven2"
    private static final int NAME_SEARCH_ROWS = 20
    private static final int API_CONNECT_TIMEOUT_MS = 3000
    private static final int API_READ_TIMEOUT_MS = 4000
    private static final int API_RETRY_COUNT = 6
    private static final int API_RETRY_WAIT_MS = 350
    /** API への最小呼び出し間隔（ms） */
    private static final int API_MIN_INTERVAL_MS = 250
    /** 429/503 などサーバー都合時のバックオフ（ms） */
    private static final int API_RATE_LIMIT_BACKOFF_MS = 1200
    private static final Object API_RATE_LOCK = new Object()
    private static long nextApiRequestAtMs = 0L
    private static final String USER_CONFIG_DIR = System.getProperty("user.home") + "/.ant-to-maven-converter/"
    /** 設定ファイルのデフォルト配置ディレクトリ */
    private static final String DEFAULT_CONFIG_DIR = System.getProperty("user.home") + "/.ant-to-maven-converter/config/"
    private static final String CONFIG_FILE = "ant-to-maven-default.groovy"
    /** プロジェクトフォルダ履歴を保存するテキストファイル（1行1パス、UTF-8） */
    private static final String PROJECT_HISTORY_FILE = "project-history.txt"
    /** 設定ファイルパス履歴を保存するテキストファイル（1行1パス、UTF-8） */
    private static final String CONFIG_HISTORY_FILE = "config-history.txt"
    /** SHA-1 検索キャッシュの保存先（user.home 配下 .ant-to-maven-converter/cache） */
    private static final String SHA1_CACHE_DIR_NAME = "cache"
    private static final String SHA1_CACHE_FILE = "sha1-cache.json"
    private static final Object SHA1_CACHE_LOCK = new Object()
    /** 未ヒット（miss）キャッシュのデフォルト有効日数。ヒットは期限なし */
    private static final int SHA1_CACHE_MISS_TTL_DAYS = 30
    /** デバッグログのデフォルト配置ディレクトリ名（user.home 配下 .ant-to-maven-converter/logs） */
    private static final String DEBUG_LOG_DIR_NAME = "logs"
    private static final DateTimeFormatter DEBUG_LOG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private static final DateTimeFormatter DEBUG_LOG_FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private static final Object DEBUG_LOG_LOCK = new Object()
    private static final int DEBUG_LOG_KEEP_COUNT = 20
    private static final String PREF_NODE = "com.example.tools.ant2maven"
    private static final String PREF_KEY_LANG = "language"
    private static final String[] LANG_CODES = ["ja", "en"]

    // --- UI コンポーネント ---
    private JFrame mainFrame
    private JTextArea logArea
    private Writer debugLogWriter
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
    private JCheckBox allSystemScopeCheck
    private JButton runButton
    private JButton stopButton
    private JButton updatePomDepsButton
    private JLabel statusLabel
    private JProgressBar progressBar

    // --- 状態管理 ---
    private AtomicBoolean isRunning = new AtomicBoolean(false)
    private ConfigObject config
    private Preferences prefs = Preferences.userRoot().node(PREF_NODE)
    private JsonSlurper jsonSlurper = new JsonSlurper()
    private Properties i18nMessages = new Properties()
    private String cachedAppVersion
    private Map sha1Cache
    private int sha1CacheHits
    private int sha1CacheFetches

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
        if (mainFrame != null) mainFrame.title = appTitle()
        if (langLabel != null) langLabel.text = i18n('ui.language')
        if (projectRootLabel != null) projectRootLabel.text = i18n('ui.projectRootPath')
        if (configFileLabel != null) configFileLabel.text = i18n('ui.configFile')
        if (showFolderProjectBtn != null) showFolderProjectBtn.text = i18n('ui.showFolder')
        if (showFolderConfigBtn != null) showFolderConfigBtn.text = i18n('ui.browseConfig')
        if (latestVersionCheck != null) latestVersionCheck.text = i18n('ui.latestVersionReplace')
        if (runButton != null) runButton.text = i18n('ui.generatePom')
        if (stopButton != null) stopButton.text = i18n('ui.stop')
        if (updatePomDepsButton != null) updatePomDepsButton.text = i18n('ui.updatePomDeps')
        if (progressBar != null) progressBar.string = i18n('ui.ready')
        if (exportCsvBtn != null) exportCsvBtn.text = i18n('ui.exportCsv')
        if (importCsvBtn != null) importCsvBtn.text = i18n('ui.importCsv')
        if (allSystemScopeCheck != null) allSystemScopeCheck.text = i18n('ui.allSystemScope')
    }

    private String i18n(String key) {
        return i18nMessages.getProperty(key, key)
    }

    private String i18n(String key, Object... args) {
        String template = i18nMessages.getProperty(key, key)
        return (args == null || args.length == 0) ? template : MessageFormat.format(template, args)
    }

    /** ウィンドウタイトル（アプリ名 + pom.xml の version） */
    private String appTitle() {
        String version = getAppVersion()
        return version ? "${i18n('app.title')} ${version}" : i18n('app.title')
    }

    /**
     * アプリバージョン。Maven がフィルタした /app.properties の project.version を優先し、
     * 無ければ JAR マニフェストの Implementation-Version を使う。
     */
    private String getAppVersion() {
        if (cachedAppVersion != null) return cachedAppVersion
        String v = readFilteredAppVersion()
        if (!v) {
            try {
                v = AntToMavenTool.package?.implementationVersion?.trim()
            } catch (Exception ignored) {
                v = null
            }
        }
        cachedAppVersion = v ?: ""
        return cachedAppVersion
    }

    private String readFilteredAppVersion() {
        def stream = getClass().getResourceAsStream("/app.properties")
        if (stream == null) return null
        try {
            Properties p = new Properties()
            p.load(new InputStreamReader(stream, "UTF-8"))
            String v = p.getProperty("version")?.trim()
            if (!v || v.contains('${')) return null
            return v
        } catch (Exception ignored) {
            return null
        } finally {
            try { stream.close() } catch (Exception ignored) {}
        }
    }

    /** デフォルトの設定ファイル配置ディレクトリ（~/.ant-to-maven-converter/config/） */
    private static File getDefaultConfigDirectory() {
        return new File(DEFAULT_CONFIG_DIR)
    }

    /** デフォルトの設定ファイルの絶対パス */
    private static String getDefaultConfigPath() {
        return new File(getDefaultConfigDirectory(), CONFIG_FILE).absolutePath
    }

    /** 履歴保存用ディレクトリ（user.home 配下の .ant-to-maven-converter）。保存時に存在しなければ作成する */
    private static File getHistoryDir() {
        return new File(USER_CONFIG_DIR)
    }

    /** プロジェクトフォルダ履歴をテキストファイルから読み込む（1行1パス、UTF-8）。ファイルが無い場合は空リスト */
    private static List<String> readProjectPathHistory() {
        def file = new File(getHistoryDir(), PROJECT_HISTORY_FILE)
        if (!file.exists() || !file.isFile()) return []
        try {
            return file.readLines("UTF-8").collect { it?.trim() }.findAll { it }
        } catch (Exception e) {
            return []
        }
    }

    /** プロジェクトフォルダ履歴をテキストファイルに保存（1行1パス、UTF-8） */
    private static void saveProjectPathHistory(List<String> items) {
        def dir = getHistoryDir()
        if (!dir.exists()) dir.mkdirs()
        def file = new File(dir, PROJECT_HISTORY_FILE)
        file.withWriter("UTF-8") { w ->
            (items ?: []).each { w.write(it + "\n") }
        }
    }

    /** 設定ファイルパス履歴をテキストファイルから読み込む。ファイルが無いか空の場合は [defaultPath] */
    private static List<String> readConfigPathHistory(String defaultPath) {
        def file = new File(getHistoryDir(), CONFIG_HISTORY_FILE)
        if (!file.exists() || !file.isFile()) return defaultPath ? [defaultPath] : []
        try {
            def list = file.readLines("UTF-8").collect { it?.trim() }.findAll { it }
            return list.isEmpty() && defaultPath ? [defaultPath] : list
        } catch (Exception e) {
            return defaultPath ? [defaultPath] : []
        }
    }

    /** 設定ファイルパス履歴をテキストファイルに保存（1行1パス、UTF-8） */
    private static void saveConfigPathHistoryToFile(List<String> items) {
        def dir = getHistoryDir()
        if (!dir.exists()) dir.mkdirs()
        def file = new File(dir, CONFIG_HISTORY_FILE)
        file.withWriter("UTF-8") { w ->
            (items ?: []).each { w.write(it + "\n") }
        }
    }

    /**
     * 設定ファイルの読み込み（~/.ant-to-maven-converter/config/ を使用。存在しない場合は同梱の ant-to-maven-default.groovy をコピーして作成）
     */
    private void setupConfig() {
        File configDir = getDefaultConfigDirectory()
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
        // LookAndFeelの設定 (FlatLaf)
        try {
            FlatLightLaf.setup()
        } catch (Exception e) {
            // Fallback to Nimbus
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName())
                        break
                    }
                }
            } catch (Exception e2) {
                // Use system default
            }
        }

        // 実行モード判定 (JAR vs IDE)
        boolean isJarExecution = this.class.getResource("AntToMavenTool.class").toString().startsWith("jar:")
        int closeOperation = isJarExecution ? JFrame.EXIT_ON_CLOSE : JFrame.DISPOSE_ON_CLOSE

        // 履歴のロード（ユーザーホーム配下 .ant-to-maven-converter のテキストファイルから）
        List<String> history = readProjectPathHistory()
        String savedLang = prefs.get(PREF_KEY_LANG, "en")
        int langIndex = LANG_CODES.findIndexOf { it == savedLang }
        if (langIndex < 0) langIndex = 0

        SwingBuilder swing = new SwingBuilder()
        swing.edt {
            mainFrame = frame(title: appTitle(), size: [800, 600],
                    defaultCloseOperation: closeOperation, locationRelativeTo: null) {
                borderLayout()

                // 上部: 設定エリア
                panel(constraints: BorderLayout.NORTH) {
                    boxLayout(axis: BoxLayout.Y_AXIS)

                    panel(alignmentX: 0f) {
                        borderLayout()
                        panel(constraints: BorderLayout.WEST) {
                            flowLayout(alignment: FlowLayout.LEFT)
                            projectRootLabel = label(text: i18n('ui.projectRootPath'))
                        }
                        panel(constraints: BorderLayout.CENTER) {
                            borderLayout()
                            pathCombo = comboBox(editable: true, items: history, preferredSize: [100, 25], constraints: BorderLayout.NORTH)
                        }
                        panel(constraints: BorderLayout.EAST) {
                            flowLayout(alignment: FlowLayout.LEFT)
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
                    }

                    panel(alignmentX: 0f) {
                        borderLayout()
                        panel(constraints: BorderLayout.WEST) {
                            flowLayout(alignment: FlowLayout.LEFT)
                            configFileLabel = label(text: i18n('ui.configFile'))
                        }
                        panel(constraints: BorderLayout.CENTER) {
                            borderLayout()
                            def defaultConfigPath = getDefaultConfigPath()
                            def oldDefaultPath = new File(DEFAULT_CONFIG_DIR, CONFIG_FILE).absolutePath
                            def configHistory = readConfigPathHistory(defaultConfigPath)
                            if (configHistory.isEmpty()) configHistory = [defaultConfigPath]
                            // 保存されていた先頭（初期表示）が旧デフォルトなら、新デフォルトに差し替え
                            if (configHistory[0] && new File(configHistory[0]).absolutePath == oldDefaultPath)
                                configHistory[0] = defaultConfigPath
                            configPathCombo = comboBox(editable: true, items: configHistory, preferredSize: [100, 25], constraints: BorderLayout.NORTH)
                        }
                        panel(constraints: BorderLayout.EAST) {
                            flowLayout(alignment: FlowLayout.LEFT)
                            showFolderConfigBtn = button(text: i18n('ui.browseConfig'), actionPerformed: { browseConfigFile() })
                        }
                    }

                    panel(alignmentX: 0f) {
                        borderLayout()
                        panel(constraints: BorderLayout.WEST) {
                            flowLayout(alignment: FlowLayout.LEFT)
                            latestVersionCheck = checkBox(text: i18n('ui.latestVersionReplace'), selected: true)
                            allSystemScopeCheck = checkBox(text: i18n('ui.allSystemScope'), selected: false)
                            runButton = button(text: i18n('ui.generatePom'), actionPerformed: { startProcess() })
                            stopButton = button(text: i18n('ui.stop'), enabled: false, actionPerformed: { stopProcess() })
                        }
                        panel(constraints: BorderLayout.EAST) {
                            flowLayout(alignment: FlowLayout.RIGHT)
                            updatePomDepsButton = button(text: i18n('ui.updatePomDeps'), actionPerformed: { updatePomDependenciesToLatest() })
                        }
                    }
                }

                // 中央: ログエリア
                scrollPane(constraints: BorderLayout.CENTER) {
                    logArea = textArea(editable: false, font: new Font("Monospaced", Font.PLAIN, 12))
                }

                // 下部: コントロール
                panel(constraints: BorderLayout.SOUTH) {
                    borderLayout()
                    panel(constraints: BorderLayout.CENTER, border: BorderFactory.createEmptyBorder(6, 6, 6, 6)) {
                        borderLayout()
                        progressBar = progressBar(visible: true, stringPainted: true, string: i18n('ui.ready'), font: new Font("Dialog", Font.PLAIN, 12))
                        widget(progressBar, constraints: BorderLayout.CENTER)
                    }
                    panel(constraints: BorderLayout.EAST) {
                        flowLayout()
                        exportCsvBtn = button(text: i18n('ui.exportCsv'), actionPerformed: { exportDependenciesToCsv() })
                        importCsvBtn = button(text: i18n('ui.importCsv'), actionPerformed: { importDependenciesFromCsv() })
                    }
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

        String configPath = configPathCombo?.selectedItem?.toString()?.trim() ?: getDefaultConfigPath()
        if (!reloadConfig(configPath, true, true)) return

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
        
        // バックグラウンドスレッドで実行
        final File pomOut = outputPomFile
        Thread.start {
            File debugFile = startDebugLog("generate-pom")
            try {
                if (debugFile) log(i18n('log.debugFile', debugFile.absolutePath))
                debugLog("projectDir=${projectDir.absolutePath}")
                debugLog("outputPom=${pomOut.absolutePath}")
                debugLog("latestVersion=${latestVersionCheck?.selected}")
                debugLog("allSystemScope=${allSystemScopeCheck?.selected}")
                processDirectory(projectDir, pomOut)
            } catch (Exception e) {
                log(i18n('msg.error') + ": ${e.message}")
                debugLog("ERROR: ${e.message}")
                debugLog(stackTraceOf(e))
                e.printStackTrace()
            } finally {
                closeDebugLog()
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

    private boolean reloadConfig(String configPath, boolean failIfMissing = false, boolean savePathHistory = false) {
        try {
            File configFile = new File(configPath)
            if (!configFile.exists() || !configFile.isFile()) {
                if (failIfMissing) {
                    String msg = i18n('msg.configFileNotFound', configPath)
                    JOptionPane.showMessageDialog(mainFrame, msg, i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
                    log(msg)
                    return false
                }
                return true
            }
            config = new ConfigSlurper().parse(configFile.toURI().toURL())
            if (savePathHistory) saveConfigPathHistory(configPath)
            log(i18n('log.configReloaded', configPath))
            return true
        } catch (Exception e) {
            log(i18n('log.configLoadWarning', e.message))
            return true
        }
    }

    /** 設定ファイルをファイル選択ダイアログで選び、コンボに表示・履歴に追加する */
    private void browseConfigFile() {
        def chooser = new JFileChooser()
        chooser.dialogTitle = i18n('msg.dialogTitle.configFile')
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(i18n('msg.configFileFilter'), 'groovy'))
        String current = configPathCombo?.selectedItem?.toString()?.trim()
        if (current) {
            def f = new File(current)
            if (f.parentFile?.exists()) chooser.currentDirectory = f.parentFile
            if (f.exists()) chooser.selectedFile = f
        }
        if (chooser.showOpenDialog(mainFrame) == JFileChooser.APPROVE_OPTION) {
            String selected = chooser.selectedFile?.absolutePath
            if (selected) {
                DefaultComboBoxModel model = (DefaultComboBoxModel) configPathCombo.model
                if (model.getIndexOf(selected) == -1) model.addElement(selected)
                configPathCombo.selectedItem = selected
                saveConfigPathHistory(selected)
            }
        }
    }

    /** 設定ファイルパスを履歴に追加して永続化（.ant-to-maven-converter 配下のテキストファイルに保存。前回使用パスを先頭に） */
    private void saveConfigPathHistory(String path) {
        if (!path?.trim()) return
        DefaultComboBoxModel model = (DefaultComboBoxModel) configPathCombo.model
        List<String> items = []
        for (int i = 0; i < model.size; i++) {
            String item = model.getElementAt(i).toString()
            if (item != path) items.add(item)
        }
        items.add(0, path)  // 前回使用パスを先頭に
        saveConfigPathHistoryToFile(items)
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

    /** プロジェクトフォルダ履歴を永続化（.ant-to-maven-converter 配下のテキストファイルに保存。前回使用パスを先頭に） */
    private void saveHistory(String path) {
        DefaultComboBoxModel model = (DefaultComboBoxModel) pathCombo.model
        List<String> items = []
        for (int i = 0; i < model.size; i++) {
            String item = model.getElementAt(i).toString()
            if (item != path) items.add(item)
        }
        items.add(0, path)  // 前回使用パスを先頭に
        saveProjectPathHistory(items)
    }

    private void processDirectory(File projectDir, File outputPomFile) {
        log(i18n('log.scanning', projectDir.absolutePath))

        def excludeJarPathPatterns = asConfigList(config.excludeJarPaths)
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
        sha1CacheHits = 0
        sha1CacheFetches = 0
        
        List<Dependency> scannedDeps = []
        int count = 0
        
        for (File jar : jars) {
            if (!isRunning.get()) break
            
            count++
            updateProgress(count, jars.size(), i18n('log.analyzing', jar.name))
            def artifact = null
            try {
                String relativeJarPath = getRelativePath(projectDir, jar)
                debugLog("---- JAR ${count}/${jars.size()} ----")
                debugLog("file=${jar.name} path=${relativeJarPath}")
                if (allSystemScopeCheck?.selected) {
                    debugLog("skip search (all system scope)")
                    addSystemScopeDependency(projectDir, jar, scannedDeps)
                    continue
                }
                String sha1 = calculateSha1(jar)
                debugLog("sha1=${sha1}")
                artifact = searchMavenCentral(sha1)
                
                if (artifact) {
                    // 日付形式バージョンの警告チェック (例: 20040616)
                    if (artifact.v ==~ /^\d{8}$/) {
                        log(i18n('log.warnDateVersion', artifact.g, artifact.a, artifact.v))
                    }
                    
                    String relativePath = getRelativePath(projectDir, jar)
                    log(i18n('log.found', jar.name, relativePath, artifact.g, artifact.a, artifact.v))
                    
                    scannedDeps << new Dependency(
                        groupId: artifact.g,
                        artifactId: artifact.a,
                        version: artifact.v,
                        scope: 'compile',
                        originalFile: jar,
                        versionSourceComment: artifact.sourceUrl ? i18n('comment.versionSourceUrl', artifact.sourceUrl) : null
                    )
                } else {
                    // SHA1 でヒットしなかった場合: a:artifactId 検索は行わず、q=JARファイル名（拡張子・バージョン番号なし）で一般検索のみ行う
                    String baseName = jar.name.toLowerCase().endsWith('.jar') ? jar.name[0..-5] : jar.name
                    String nameForSearch = stripVersionFromJarBaseName(baseName)
                    debugLog("SHA-1 miss; fallback query='${nameForSearch}' (baseName='${baseName}')")
                    if (nameForSearch) Thread.sleep(200)  // レートリミット対策
                    def fallback = nameForSearch ? searchMavenCentralByQuery(nameForSearch) : null
                    if (fallback) {
                        Thread.sleep(200)  // レートリミット対策
                        def latestInfo = getLatestVersionInfo(fallback.g, fallback.a)
                        String latestVer = latestInfo?.version
                        if (latestVer) {
                            String relativePath = getRelativePath(projectDir, jar)
                            log(i18n('log.foundByArtifactId', jar.name, relativePath, fallback.g, fallback.a, latestVer))
                            scannedDeps << new Dependency(
                                groupId: fallback.g,
                                artifactId: fallback.a,
                                version: latestVer,
                                scope: 'compile',
                                originalFile: jar,
                                versionSourceComment: latestInfo?.sourceUrl ? i18n('comment.versionSourceUrl', latestInfo.sourceUrl) : null
                            )
                        } else {
                            addSystemScopeDependency(projectDir, jar, scannedDeps)
                        }
                    } else {
                        addSystemScopeDependency(projectDir, jar, scannedDeps)
                    }
                }
            } catch (Exception e) {
                log(i18n('log.errorProcessing', jar.name, e.message))
                debugLog("ERROR processing ${jar.name}: ${e.message}")
                debugLog(stackTraceOf(e))
            }
            
            // SHA-1 キャッシュヒット時は追加の待機を省略
            if (!artifact?.fromCache) {
                Thread.sleep(200)
            }
        }

        if (sha1CacheHits > 0 || sha1CacheFetches > 0) {
            log(i18n('log.sha1CacheStats', sha1CacheHits.toString(), sha1CacheFetches.toString()))
            debugLog("SHA-1 cache hits=${sha1CacheHits} fetches=${sha1CacheFetches}")
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
        def excludes = asConfigList(config.excludeDependencies)
        def additions = asConfigList(config.addDependencies)
        def replacements = config.replaceDependencies instanceof Map ? config.replaceDependencies : [:]
        def excludeFromVersionUpgrade = asConfigList(config.excludeFromVersionUpgrade)

        debugLog("excludes=${excludes}")
        debugLog("additions=${additions}")
        debugLog("replacements.keys=${replacements instanceof Map ? replacements.keySet() : []}")

        // 1. addDependencies を先に確定（検出済みと同じ G:A でも設定を優先し、先頭に置く）
        List<Dependency> addedDeps = []
        additions.each { add ->
            Dependency parsed = parseConfiguredDependency(add)
            if (!parsed) {
                log(i18n('log.addSkippedInvalid', String.valueOf(add)))
                debugLog("addDependencies skip (invalid): ${add}")
                return
            }
            String key = "${parsed.groupId}:${parsed.artifactId}"
            if (processedKeys.contains(key)) {
                debugLog("addDependencies skip (duplicate in config): ${key}")
                return
            }
            parsed.dependencyComment = i18n('comment.add', parsed.groupId, parsed.artifactId, parsed.version)
            addedDeps << parsed
            processedKeys.add(key)
            log(i18n('log.added', parsed.groupId, parsed.artifactId, parsed.version))
        }

        // 2. 除外と置換の適用 (scannedDependenciesを使用)
        scannedDependencies.each { dep ->
            String key = "${dep.groupId}:${dep.artifactId}"
            if (processedKeys.contains(key)) {
                debugLog("scanned ${key} skipped; already in addDependencies")
                return
            }
            
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

        // 3. バージョンアップの適用（「バージョンを最新にする」がオンのとき、追加・除外・置換後の一覧に対して実施）
        List<Dependency> scannedOrReplaced = finalDependencies.sort { it.groupId ?: '' }
        finalDependencies = addedDeps + scannedOrReplaced
        if (latestVersionCheck?.selected) {
            log("\n" + i18n('log.versionUpgrade'))
            finalDependencies.each { dep ->
                if (!isRunning.get()) return
                if (dep.scope == 'system' || dep.systemPath) return
                String key = "${dep.groupId}:${dep.artifactId}"
                def excludeEntry = excludeFromVersionUpgrade.find { entry ->
                    if (entry instanceof String) return entry == key
                    if (entry instanceof Map) {
                        String entryKey = entry.key ?: (entry.groupId && entry.artifactId ? "${entry.groupId}:${entry.artifactId}" : null)
                        return entryKey == key
                    }
                    return false
                }
                if (excludeEntry != null) {
                    String fixedVersion = null
                    if (excludeEntry instanceof Map && (excludeEntry.version || excludeEntry.get('version'))) {
                        fixedVersion = excludeEntry.version ?: excludeEntry.get('version')?.toString()
                    }
                    if (fixedVersion != null && fixedVersion != dep.version) {
                        log(i18n('log.versionPinned', dep.groupId, dep.artifactId, dep.version, fixedVersion))
                        dep.versionComment = i18n('comment.versionPinned', dep.version, fixedVersion)
                        dep.version = fixedVersion
                    } else {
                        log(i18n('log.versionUpgradeSkipped', key))
                    }
                    return
                }
                def latestInfo = getLatestVersionInfo(dep.groupId, dep.artifactId, dep.version)
                String latest = latestInfo?.version
                if (latest && isNewerVersion(latest, dep.version)) {
                    log(i18n('log.versionUpgraded', dep.groupId, dep.artifactId, dep.version, latest))
                    dep.versionComment = i18n('comment.versionUpgrade', dep.version, latest)
                    dep.versionSourceComment = latestInfo?.sourceUrl ? i18n('comment.versionSourceUrl', latestInfo.sourceUrl) : null
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

    /** ConfigSlurper のリスト／単一 Map／空 ConfigObject を List に正規化する */
    private static List asConfigList(def value) {
        if (value == null) return []
        if (value instanceof ConfigObject) {
            if (value.isEmpty()) return []
            if (value.containsKey('groupId') || value.containsKey('artifactId') || value.containsKey('key')) {
                return [value]
            }
            return value.values().toList().findAll { it != null }
        }
        if (value instanceof Collection) return value.toList()
        if (value instanceof Map) return [value]
        return [value]
    }

    /** addDependencies の 1 要素（"g:a:v" または Map）を Dependency にする。不正なら null */
    private static Dependency parseConfiguredDependency(def add) {
        String g = null, a = null, v = null, s = null, c = null
        if (add instanceof CharSequence) {
            def parts = add.toString().trim().split(':')
            if (parts.length >= 3) {
                g = parts[0]
                a = parts[1]
                v = parts[2]
            }
        } else if (add instanceof Map) {
            g = configEntryString(add, 'groupId')
            a = configEntryString(add, 'artifactId')
            v = configEntryString(add, 'version')
            s = configEntryString(add, 'scope')
            c = configEntryString(add, 'classifier')
        }
        if (!g || !a || !v) return null
        return new Dependency(
            groupId: g,
            artifactId: a,
            version: v,
            scope: s ?: 'compile',
            classifier: c
        )
    }

    private static String configEntryString(def map, String key) {
        def val
        try {
            val = map[key]
        } catch (Exception ignored) {
            val = null
        }
        if (val == null && map instanceof Map) val = map.get(key)
        if (val == null) return null
        if (val instanceof ConfigObject && ((ConfigObject) val).isEmpty()) return null
        String text = val.toString()?.trim()
        return text ? text : null
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

    /**
     * プロジェクトの pom.xml の依存関係バージョンを Maven Central の最新に更新する。
     * DOM パーサー（DocumentBuilderFactory）でパースし、setIgnoringComments(false) によりコメントを保持したまま
     * 各 dependency の version のみを書き換え、Transformer でシリアライズして保存する。
     */
    private void updatePomDependenciesToLatest() {
        // 対象 pom.xml の取得（コンボで選択中のプロジェクトパスから）
        File pomFile = getProjectPomFile()
        if (!pomFile) {
            JOptionPane.showMessageDialog(mainFrame,
                i18n('msg.updatePom.needPom'),
                i18n('ui.updatePomDeps'), JOptionPane.WARNING_MESSAGE)
            return
        }

        String configPath = configPathCombo?.selectedItem?.toString()?.trim() ?: getDefaultConfigPath()
        if (!reloadConfig(configPath, true, false)) return

        // 依存が1件も無い場合は処理しない
        List<Dependency> deps = parseDependenciesFromPom(pomFile)
        if (deps.isEmpty()) {
            JOptionPane.showMessageDialog(mainFrame, i18n('msg.updatePom.noDeps'), i18n('ui.updatePomDeps'), JOptionPane.INFORMATION_MESSAGE)
            return
        }
        // 上書き確認（上書き・別名保存・取り消し）
        File outputPomFile = pomFile
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
            outputPomFile = new File(pomFile.parentFile, "pom_updated_${System.currentTimeMillis()}.xml")
        }

        final File pom = pomFile
        final File pomOut = outputPomFile
        updatePomDepsButton.enabled = false
        logArea.text = ""
        log(i18n('log.updatePom.start'))
        // ネットワークアクセスがあるためバックグラウンドスレッドで実行
        Thread.start {
            int updated = 0
            File debugFile = startDebugLog("update-pom-deps")
            try {
                if (debugFile) log(i18n('log.debugFile', debugFile.absolutePath))
                debugLog("pom=${pom.absolutePath}")
                debugLog("outputPom=${pomOut.absolutePath}")
                def excludeFromVersionUpgrade = asConfigList(config.excludeFromVersionUpgrade)

                // DOM パーサー（コメントを保持する設定）
                def factory = DocumentBuilderFactory.newInstance()
                factory.setIgnoringComments(false)
                factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
                def builder = factory.newDocumentBuilder()
                Document doc = builder.parse(pom)
                // 全 <dependency> 要素を取得（dependencies / dependencyManagement 内を含む）
                NodeList depList = doc.getElementsByTagName('dependency')
                for (int i = 0; i < depList.length; i++) {
                    Element dep = (Element) depList.item(i)
                    String g = getFirstChildElementText(dep, 'groupId')
                    String a = getFirstChildElementText(dep, 'artifactId')
                    Element versionEl = getFirstChildElement(dep, 'version')
                    if (!g || !a || !versionEl) continue
                    String currentVer = versionEl.getTextContent()?.trim()
                    if (!currentVer) continue
                    // 設定で最新化対象外の場合はスキップ
                    String key = "${g}:${a}"
                    if (isExcludedFromVersionUpgrade(key, excludeFromVersionUpgrade)) {
                        log(i18n('log.versionUpgradeSkipped', key))
                        continue
                    }
                    String latest = getLatestVersion(g, a, currentVer)
                    debugLog("update pom dep ${g}:${a} current=${currentVer} latest=${latest}")
                    if (latest && isNewerVersion(latest, currentVer)) {
                        versionEl.setTextContent(latest)
                        updated++
                        log(i18n('log.updatePom.updated', g, a, currentVer, latest))
                    } else if (!latest) {
                        log(i18n('log.updatePom.skipped', g, a))
                    }
                    // latest が currentVer 以下なら更新しない（バージョンダウン防止）
                }
                if (updated > 0) {
                    String xml = serializeDomDocument(doc)
                    pomOut.withWriter('UTF-8') { w -> w.write(xml) }
                }
                log(i18n('log.updatePom.complete'))
                final int count = updated
                final String outPath = pomOut.absolutePath
                SwingUtilities.invokeLater {
                    updatePomDepsButton.enabled = true
                    JOptionPane.showMessageDialog(mainFrame,
                        i18n('msg.updatePom.done', count.toString(), outPath),
                        i18n('ui.updatePomDeps'), JOptionPane.INFORMATION_MESSAGE)
                }
            } catch (Exception e) {
                log(i18n('msg.updatePom.failed', e.message))
                debugLog("ERROR: ${e.message}")
                debugLog(stackTraceOf(e))
                e.printStackTrace()
                SwingUtilities.invokeLater {
                    updatePomDepsButton.enabled = true
                    JOptionPane.showMessageDialog(mainFrame, i18n('msg.updatePom.failed', e.message), i18n('msg.error'), JOptionPane.ERROR_MESSAGE)
                }
            } finally {
                closeDebugLog()
            }
        }
    }

    /** 設定の excludeFromVersionUpgrade に groupId:artifactId が含まれるか（最新化対象外なら true） */
    private static boolean isExcludedFromVersionUpgrade(String key, Collection excludeFromVersionUpgrade) {
        if (!excludeFromVersionUpgrade) return false
        return excludeFromVersionUpgrade.find { entry ->
            if (entry instanceof String) return entry == key
            if (entry instanceof Map) {
                String entryKey = entry.key ?: (entry.groupId && entry.artifactId ? "${entry.groupId}:${entry.artifactId}" : null)
                return entryKey == key
            }
            return false
        } != null
    }

    /** DOM の Element から指定タグ名の直下の最初の子要素を返す。無ければ null */
    private static Element getFirstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes()
        for (int i = 0; i < children.getLength(); i++) {
            def n = children.item(i)
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && tagName.equals(n.getNodeName()))
                return (Element) n
        }
        return null
    }

    /** DOM の Element から指定タグ名の直下の最初の子要素のテキスト内容を返す。無ければ null */
    private static String getFirstChildElementText(Element parent, String tagName) {
        Element el = getFirstChildElement(parent, tagName)
        return el?.getTextContent()?.trim()
    }

    /** DOM Document を XML 文字列にシリアライズする（コメント・構造を保持）。余計な空行は1行にまとめる。 */
    private static String serializeDomDocument(Document doc) {
        def transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, 'UTF-8')
        transformer.setOutputProperty(OutputKeys.INDENT, 'yes')
        def writer = new StringWriter()
        transformer.transform(new DOMSource(doc), new StreamResult(writer))
        // 連続する空行（改行＋空白のみの行）を1つの改行にまとめる
        return writer.toString().replaceAll(/\n\s*\n/, '\n')
    }

    // --- ヘルパーメソッド ---
    /** MarkupBuilder に <dependencies> ブロックを書き出す。除外・追加・置換のコメントを付与 */
    private void buildDependenciesSection(MarkupBuilder builder, List<Dependency> finalDependencies, List<String> excludedKeys = []) {
        builder.dependencies {
            if (excludedKeys) {
                excludedKeys.each { key -> mkp.yieldUnescaped('\n  <!-- ' + i18n('comment.excluded', key) + ' -->') }
            }
            finalDependencies.each { dep ->
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
                    if (dep.versionSourceComment) {
                        mkp.comment(dep.versionSourceComment)
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

    /** API 呼び出し間隔を制御（短時間の連続アクセスを抑止） */
    private void waitForApiRateLimitSlot() {
        int minIntervalMs = getApiConfigInt('apiMinIntervalMs', API_MIN_INTERVAL_MS)
        synchronized (API_RATE_LOCK) {
            long now = System.currentTimeMillis()
            long waitMs = nextApiRequestAtMs - now
            if (waitMs > 0) {
                Thread.sleep(waitMs)
            }
            nextApiRequestAtMs = System.currentTimeMillis() + minIntervalMs
        }
    }

    /** HTTP GET（UTF-8）。429/503 は IOException として返す */
    private String fetchUrlText(String url, int connectTimeoutMs, int readTimeoutMs, String userAgent = null) {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(connectTimeoutMs)
        conn.setReadTimeout(readTimeoutMs)
        conn.setRequestProperty("User-Agent", userAgent ?: "ant-to-maven-converter")
        if (userAgent) {
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

        int status = conn.responseCode
        debugLog("HTTP GET ${url}")
        debugLog("  status=${status}")
        if (status == 429 || status == 503) {
            throw new IOException("HTTP ${status}")
        }
        if (status >= 400) {
            throw new IOException("HTTP ${status}")
        }
        String body = conn.inputStream.getText("UTF-8")
        debugLog("  bodyChars=${body?.length() ?: 0}")
        return body
    }

    /** HTTP テキスト取得（タイムアウト/レートリミット時は待機してリトライ） */
    private String fetchUrlTextWithRetry(String url, Integer connectTimeoutMs = null, Integer readTimeoutMs = null, Integer retryCount = null, String userAgent = null) {
        int connectTimeout = connectTimeoutMs != null ? connectTimeoutMs : getApiConfigInt('apiConnectTimeoutMs', API_CONNECT_TIMEOUT_MS)
        int readTimeout = readTimeoutMs != null ? readTimeoutMs : getApiConfigInt('apiReadTimeoutMs', API_READ_TIMEOUT_MS)
        int retries = retryCount != null ? retryCount : getApiConfigInt('apiRetryCount', API_RETRY_COUNT)
        int retryWaitMs = getApiConfigInt('apiRetryWaitMs', API_RETRY_WAIT_MS)
        int rateLimitBackoffMs = getApiConfigInt('apiRateLimitBackoffMs', API_RATE_LIMIT_BACKOFF_MS)

        Exception lastError = null
        int attempts = Math.max(1, retries)
        for (int i = 1; i <= attempts; i++) {
            try {
                waitForApiRateLimitSlot()
                return fetchUrlText(url, connectTimeout, readTimeout, userAgent)
            } catch (Exception e) {
                lastError = e
                boolean isTimeout = (e instanceof java.net.SocketTimeoutException) || (e.cause instanceof java.net.SocketTimeoutException)
                boolean isRateLimited = e.message?.contains("HTTP 429") || e.message?.contains("HTTP 503")
                debugLog("  attempt ${i}/${attempts} failed: ${e.message} timeout=${isTimeout} rateLimited=${isRateLimited}")
                if ((!isTimeout && !isRateLimited) || i >= attempts) break
                try {
                    Thread.sleep(isRateLimited ? rateLimitBackoffMs : retryWaitMs)
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw lastError
    }

    /** 設定ファイルの API 設定値を int として取得（未設定/不正値は defaultValue） */
    private int getApiConfigInt(String key, int defaultValue) {
        try {
            def value = config?."${key}"
            if (value == null) return defaultValue
            if (value instanceof Number) return ((Number) value).intValue()
            String text = value.toString()?.trim()
            return text ? Integer.parseInt(text) : defaultValue
        } catch (Exception ignored) {
            return defaultValue
        }
    }

    private def searchMavenCentral(String sha1) {
        String key = normalizeSha1(sha1)
        def cached = lookupSha1Cache(key)
        if (cached != null) {
            sha1CacheHits++
            if (cached.miss) {
                debugLog("SHA-1 cache miss-entry ${key}")
                return null
            }
            debugLog("SHA-1 cache hit ${key} -> ${cached.g}:${cached.a}:${cached.v}")
            return [g: cached.g, a: cached.a, v: cached.v, sourceUrl: cached.sourceUrl, fromCache: true]
        }

        String url = null
        try {
            sha1CacheFetches++
            // クエリパラメータをURLエンコード
            String query = "1:\"${key}\""
            String encodedQuery = URLEncoder.encode(query, "UTF-8")
            url = "${MAVEN_SHA1_SEARCH_API}?q=${encodedQuery}&rows=1&wt=json"
            debugLog("SHA-1 search query=${query}")
            debugLog("  URL: ${url}")

            String jsonText = fetchUrlTextWithRetry(url)
            def json = jsonSlurper.parseText(jsonText)
            debugLogSearchHits(json)
            if (json.response.numFound > 0) {
                def doc = json.response.docs[0]
                def result = [g: doc.g, a: doc.a, v: doc.v, sourceUrl: url]
                storeSha1CacheHit(key, result)
                return result
            }
            storeSha1CacheMiss(key)
        } catch (Exception e) {
            log(i18n('log.apiError', url, e.message))
            debugLog("SHA-1 search error: ${e.message}")
        }
        return null
    }

    private static String normalizeSha1(String sha1) {
        return sha1?.trim()?.toLowerCase() ?: ""
    }

    private boolean isSha1CacheEnabled() {
        return getConfigBoolean("sha1CacheEnabled", true)
    }

    private File getSha1CacheFile() {
        try {
            def value = config?.sha1CacheFile
            if (value instanceof CharSequence) {
                String text = value.toString().trim()
                if (text) return new File(text)
            }
        } catch (Exception ignored) {
        }
        return new File(new File(USER_CONFIG_DIR, SHA1_CACHE_DIR_NAME), SHA1_CACHE_FILE)
    }

    /** 未ヒットキャッシュの TTL（ミリ秒）。0 以下なら未ヒットはキャッシュしない */
    private long getSha1CacheMissTtlMs() {
        int days = getApiConfigInt("sha1CacheMissTtlDays", SHA1_CACHE_MISS_TTL_DAYS)
        return days > 0 ? days * 24L * 60L * 60L * 1000L : 0L
    }

    private boolean getConfigBoolean(String key, boolean defaultValue) {
        try {
            def value = config?."${key}"
            if (value == null) return defaultValue
            if (value instanceof ConfigObject) return ((ConfigObject) value).isEmpty() ? defaultValue : Boolean.parseBoolean(value.toString())
            if (value instanceof Boolean) return (Boolean) value
            String text = value.toString()?.trim()
            if (!text) return defaultValue
            return Boolean.parseBoolean(text)
        } catch (Exception ignored) {
            return defaultValue
        }
    }

    private void ensureSha1CacheLoaded() {
        if (sha1Cache != null) return
        synchronized (SHA1_CACHE_LOCK) {
            if (sha1Cache != null) return
            Map loaded = [:]
            if (isSha1CacheEnabled()) {
                File file = getSha1CacheFile()
                if (file.exists() && file.isFile()) {
                    try {
                        def parsed = file.withReader("UTF-8") { r -> jsonSlurper.parse(r) }
                        if (parsed instanceof Map) {
                            parsed.each { k, v ->
                                if (k) loaded[k.toString().toLowerCase()] = v
                            }
                        }
                        debugLog("SHA-1 cache loaded entries=${loaded.size()} file=${file.absolutePath}")
                    } catch (Exception e) {
                        debugLog("SHA-1 cache load failed: ${e.message}")
                    }
                }
            }
            sha1Cache = loaded
        }
    }

    /** ヒット Map / 未ヒット [miss:true] / 未登録なら null */
    private def lookupSha1Cache(String sha1) {
        if (!sha1 || !isSha1CacheEnabled()) return null
        ensureSha1CacheLoaded()
        def entry
        synchronized (SHA1_CACHE_LOCK) {
            entry = sha1Cache[sha1]
        }
        if (!(entry instanceof Map)) return null
        if (entry.miss) {
            long ttl = getSha1CacheMissTtlMs()
            if (ttl <= 0) return null
            long ts = toLong(entry.ts)
            if (ts > 0 && (System.currentTimeMillis() - ts) > ttl) {
                debugLog("SHA-1 cache miss-entry expired ${sha1}")
                return null
            }
            return [miss: true]
        }
        String g = entry.g?.toString()
        String a = entry.a?.toString()
        String v = entry.v?.toString()
        if (!g || !a || !v) return null
        return [g: g, a: a, v: v, sourceUrl: entry.sourceUrl?.toString()]
    }

    private void storeSha1CacheHit(String sha1, def result) {
        if (!sha1 || !isSha1CacheEnabled() || !result?.g || !result?.a || !result?.v) return
        synchronized (SHA1_CACHE_LOCK) {
            ensureSha1CacheLoaded()
            sha1Cache[sha1] = [
                g: result.g.toString(),
                a: result.a.toString(),
                v: result.v.toString(),
                sourceUrl: result.sourceUrl?.toString(),
                ts: System.currentTimeMillis()
            ]
        }
        persistSha1Cache()
    }

    private void storeSha1CacheMiss(String sha1) {
        if (!sha1 || !isSha1CacheEnabled()) return
        if (getSha1CacheMissTtlMs() <= 0) return
        synchronized (SHA1_CACHE_LOCK) {
            ensureSha1CacheLoaded()
            sha1Cache[sha1] = [miss: true, ts: System.currentTimeMillis()]
        }
        persistSha1Cache()
    }

    private void persistSha1Cache() {
        if (!isSha1CacheEnabled()) return
        synchronized (SHA1_CACHE_LOCK) {
            try {
                File file = getSha1CacheFile()
                File dir = file.parentFile
                if (dir && !dir.exists()) dir.mkdirs()
                File tmp = new File(dir, file.name + ".tmp")
                tmp.setText(JsonOutput.prettyPrint(JsonOutput.toJson(sha1Cache ?: [:])), "UTF-8")
                if (file.exists() && !file.delete()) {
                    debugLog("SHA-1 cache replace failed: ${file.absolutePath}")
                    return
                }
                if (!tmp.renameTo(file)) {
                    tmp.withInputStream { ins -> file.withOutputStream { outs -> ins.transferTo(outs) } }
                    tmp.delete()
                }
            } catch (Exception e) {
                debugLog("SHA-1 cache save failed: ${e.message}")
            }
        }
    }

    private static long toLong(def value) {
        if (value instanceof Number) return ((Number) value).longValue()
        try {
            return value ? Long.parseLong(value.toString()) : 0L
        } catch (Exception ignored) {
            return 0L
        }
    }

    /**
     * JAR のベース名（拡張子除いた名前）から末尾のバージョン部分を除去する。
     * 例: primefaces-15.0.5 -> primefaces, commons-lang3-3.12.0 -> commons-lang3
     * バージョンは「-数字」で始まり、その後に .数字 や -qualifier が続く形式をマッチする。
     */
    private static String stripVersionFromJarBaseName(String baseName) {
        if (!baseName?.trim()) return baseName ?: ''
        // -数字(.数字)*(- qualifier)? の形式。例: -15.0.5, -3.12.0, -1.0.0-SNAPSHOT
        String withoutVer = baseName.replaceFirst(/-\d+(\.\d+)*(-[a-zA-Z0-9.]+)?$/, '')
        (withoutVer != null && withoutVer != baseName) ? withoutVer.trim() : baseName
    }

    /**
     * artifactId で Maven Central を検索し、人気（versionCount）が最大の groupId / artifactId を返す。
     * バージョンは getLatestVersion で取得すること。
     */
    private def searchMavenCentralByArtifactId(String artifactId) {
        return searchMavenCentralName(artifactId, true)
    }

    /**
     * 名前検索。Maven Central を a:クエリ で複数件取得し、artifactId 一致かつ versionCount 最大
     * （公開されている利用の多さの近似。mvnrepository の sort=popular 相当）を選ぶ。
     * ヒットが無ければ一般 q= 検索にフォールバックする。
     * central.sonatype.com は Solr の引用符付きフレーズ（a:"name"）を解釈しないため引用符は付けない。
     */
    private def searchMavenCentralByQuery(String query) {
        def hit = searchMavenCentralName(query, true)
        if (hit) return hit
        debugLog("name search: a: miss; fallback to general q=")
        return searchMavenCentralName(query, false)
    }

    /**
     * Maven Central Search API で名前検索する。
     * artifactIdQuery=true なら q=a:name、false なら q=name。いずれも rows 件取得して versionCount で選ぶ。
     */
    private def searchMavenCentralName(String query, boolean artifactIdQuery) {
        String url = null
        try {
            String q = artifactIdQuery ? "a:${query}" : query
            url = "${MAVEN_SEARCH_API}?q=${URLEncoder.encode(q, "UTF-8")}&rows=${NAME_SEARCH_ROWS}&wt=json"
            debugLog("name search query=${q}")
            debugLog("  URL: ${url}")
            String jsonText = fetchUrlTextWithRetry(url)
            def json = jsonSlurper.parseText(jsonText)
            return selectMavenNameSearchHit(json, query)
        } catch (Exception e) {
            debugLog("name search error: ${e.message}")
            return null
        }
    }

    /**
     * artifactId がクエリと一致するドキュメントを優先し、その中で versionCount が最大のものを返す。
     * versionCount はリリース数で、人気順の近似として使う。
     */
    private def selectMavenNameSearchHit(def json, String query) {
        def docs = json?.response?.docs
        def numFound = json?.response?.numFound
        int shown = (docs instanceof Collection) ? docs.size() : 0
        debugLog("  numFound=${numFound} shown=${shown}")
        if (!(docs instanceof Collection) || docs.isEmpty()) {
            debugLog("  hit: none")
            return null
        }
        docs.eachWithIndex { doc, i ->
            debugLog("  hit[${i}]: g=${doc.g} a=${doc.a} latest=${doc.latestVersion ?: doc.v} versionCount=${doc.versionCount} id=${doc.id}")
        }
        String q = query?.trim()?.toLowerCase()
        def exact = docs.findAll { it.a?.toString()?.toLowerCase() == q }
        def pool = exact ? exact : docs
        def selected = pool.max { doc ->
            def vc = doc.versionCount
            (vc instanceof Number) ? ((Number) vc).intValue() : 0
        }
        if (!selected) {
            debugLog("  hit: none")
            return null
        }
        debugLog("  selected: g=${selected.g} a=${selected.a} versionCount=${selected.versionCount} (exactArtifactId=${!exact.isEmpty()})")
        return [g: selected.g, a: selected.a]
    }

    /** SHA1 でも名前検索でも見つからなかった場合に system スコープで依存を追加する */
    private void addSystemScopeDependency(File projectDir, File jar, List<Dependency> scannedDeps) {
        String relativePath = getRelativePath(projectDir, jar)
        boolean forceAllSystemScope = allSystemScopeCheck?.selected ?: false
        log(i18n('log.notFound', jar.name, relativePath))
        debugLog("system scope fallback: ${jar.name} path=${relativePath}")
        scannedDeps << new Dependency(
            groupId: 'local.dependency',
            artifactId: jar.name.replace('.jar', ''),
            version: '1.0.0',
            scope: 'system',
            systemPath: "\${project.basedir}/${relativePath}",
            originalFile: jar,
            dependencyComment: forceAllSystemScope ? null : i18n('comment.systemScope')
        )
    }

    private static final List<String> DEFAULT_PRE_RELEASE_PATTERNS = ['alpha', 'beta', '-rc', '.rc', 'snapshot', 'milestone', 'preview']

    /** バージョンがプレリリース版かどうか（バージョンアップ時に除外する）。patterns 未指定時は DEFAULT_PRE_RELEASE_PATTERNS を使用 */
    private static boolean isPreReleaseVersion(String version, List<String> patterns = null) {
        if (!version?.trim()) return false
        String v = version.trim().toLowerCase()
        def list = (patterns != null && !patterns.isEmpty())
                ? patterns.collect { it?.toString()?.trim()?.toLowerCase() }.findAll { it }
                : DEFAULT_PRE_RELEASE_PATTERNS
        return list.any { v.contains(it) }
    }

    /** バージョン文字列からメジャーバージョン（先頭の数値）を取得。取得できない場合は null */
    private static Integer getMajorVersion(String version) {
        if (!version?.trim()) return null
        def m = (version.trim() =~ /[^\d]*(\d+)/)
        return m.find() ? m.group(1).toInteger() : null
    }

    /**
     * Maven Central の maven-metadata.xml から最新バージョンを取得する。
     * alpha / beta / rc / SNAPSHOT 等のプレリリース版は除外し、安定版のみから最新を選ぶ。
     * currentVersion を指定した場合は同じメジャーバージョン内の最新のみを対象とする。
     * REST API は最新情報の反映にタイムラグがあるため、metadata.xml を参照する。
     * 例: https://repo1.maven.org/maven2/org/primefaces/primefaces/maven-metadata.xml
     */
    private String getLatestVersion(String groupId, String artifactId, String currentVersion = null) {
        return getLatestVersionInfo(groupId, artifactId, currentVersion)?.version
    }

    /** 最新バージョンと、その判定に使用した metadata URL を返す */
    private Map getLatestVersionInfo(String groupId, String artifactId, String currentVersion = null) {
        String metadataUrl = null
        try {
            String pathSegment = groupId.replace('.', '/')
            metadataUrl = "${MAVEN_REPO_BASE}/${pathSegment}/${artifactId}/maven-metadata.xml"
            debugLog("latest version lookup: ${groupId}:${artifactId} current=${currentVersion}")
            debugLog("  URL: ${metadataUrl}")
            String xmlText = fetchUrlTextWithRetry(metadataUrl, API_CONNECT_TIMEOUT_MS, 20000, API_RETRY_COUNT)
            def root = new XmlSlurper().parseText(xmlText)
            def versioning = root.versioning
            if (!versioning) {
                debugLog("  metadata: no <versioning>")
                return null
            }
            String latest = versioning.latest?.text()
            String release = versioning.release?.text()
            String lastUpdated = versioning.lastUpdated?.text()
            debugLog("  metadata.latest=${latest} release=${release} lastUpdated=${lastUpdated}")
            def versionList = versioning.versions?.version?.collect { it.text()?.trim() }?.findAll { it } as List
            if (!versionList || versionList.isEmpty()) {
                debugLog("  versions: empty")
                return null
            }
            debugLog("  versions.count=${versionList.size()}")
            def preReleasePatterns = config?.preReleaseVersionPatterns instanceof Collection ? config.preReleaseVersionPatterns : null
            // プレリリース版（設定またはデフォルトのパターンに該当するもの）を除外
            def stableList = versionList.findAll { !isPreReleaseVersion(it, preReleasePatterns) }
            if (stableList.isEmpty()) {
                debugLog("  stable versions: empty (all pre-release)")
                return null
            }
            debugLog("  stable.count=${stableList.size()}")
            // メジャーバージョンを変えない: currentVersion が指定されていれば同じメジャーに絞る
            Integer major = getMajorVersion(currentVersion)
            def candidateList = (major != null)
                    ? stableList.findAll { getMajorVersion(it) == major }
                    : stableList
            if (candidateList.isEmpty()) {
                debugLog("  candidates: empty (major=${major})")
                return null
            }
            String maxVer = candidateList.max { String a, String b ->
                try {
                    new ComparableVersion(a).compareTo(new ComparableVersion(b))
                } catch (Exception e) { 0 }
            }
            debugLog("  selected=${maxVer} (majorFilter=${major})")
            return [version: maxVer, sourceUrl: metadataUrl]
        } catch (Exception e) {
            debugLog("  latest version lookup error: ${e.message}")
        }
        return null
    }

    /** API で取得したバージョンが現在より新しい場合のみ true（バージョンダウンを防ぐ） */
    private static boolean isNewerVersion(String newVersion, String currentVersion) {
        if (!newVersion?.trim() || !currentVersion?.trim()) return false
        try {
            return new ComparableVersion(newVersion.trim()).compareTo(new ComparableVersion(currentVersion.trim())) > 0
        } catch (Exception e) {
            return false
        }
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

    /** デバッグログを有効にするか。未設定時は true。ConfigSlurper の空 ConfigObject は未設定とみなす */
    private boolean isDebugLogEnabled() {
        try {
            def value = config?.debugLogEnabled
            if (value == null) return true
            if (value instanceof ConfigObject) return ((ConfigObject) value).isEmpty()
            if (value instanceof Boolean) return (Boolean) value
            String text = value.toString()?.trim()
            if (!text) return true
            return Boolean.parseBoolean(text)
        } catch (Exception ignored) {
            return true
        }
    }

    /** デバッグログの出力先ディレクトリ。設定 debugLogDir があればそれを使用 */
    private File getDebugLogDir() {
        try {
            def value = config?.debugLogDir
            if (value instanceof CharSequence) {
                String text = value.toString().trim()
                if (text) return new File(text)
            } else if (value instanceof File) {
                return (File) value
            }
        } catch (Exception ignored) {
        }
        return new File(USER_CONFIG_DIR, DEBUG_LOG_DIR_NAME)
    }

    /** 処理開始時にデバッグログファイルを開く。失敗時は null */
    private File startDebugLog(String operation) {
        closeDebugLog()
        if (!isDebugLogEnabled()) return null
        try {
            File dir = getDebugLogDir()
            if (!dir.exists()) dir.mkdirs()
            pruneDebugLogs(dir)
            String ts = LocalDateTime.now().format(DEBUG_LOG_FILE_TS)
            File file = new File(dir, "debug-${ts}.log")
            Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"))
            synchronized (DEBUG_LOG_LOCK) {
                debugLogWriter = w
            }
            debugLog("=== debug log start ===")
            debugLog("operation=${operation}")
            debugLog("java.version=${System.getProperty('java.version')}")
            return file
        } catch (Exception e) {
            log(i18n('log.debugFileFailed', e.message))
            return null
        }
    }

    /** 古いデバッグログを削除し、直近 DEBUG_LOG_KEEP_COUNT 件だけ残す */
    private static void pruneDebugLogs(File dir) {
        try {
            List<File> files = dir.listFiles()?.findAll { File f ->
                f.isFile() && f.name.startsWith("debug-") && f.name.endsWith(".log")
            }?.sort { it.lastModified() }
            if (!files || files.size() <= DEBUG_LOG_KEEP_COUNT) return
            files.take(files.size() - DEBUG_LOG_KEEP_COUNT).each { it.delete() }
        } catch (Exception ignored) {
        }
    }

    private void closeDebugLog() {
        synchronized (DEBUG_LOG_LOCK) {
            if (debugLogWriter == null) return
            try {
                debugLogWriter.write(formatDebugLine("=== debug log end ==="))
                debugLogWriter.flush()
                debugLogWriter.close()
            } catch (Exception ignored) {
            }
            debugLogWriter = null
        }
    }

    private void debugLog(String message) {
        synchronized (DEBUG_LOG_LOCK) {
            if (debugLogWriter == null) return
            try {
                debugLogWriter.write(formatDebugLine(message))
                debugLogWriter.flush()
            } catch (Exception ignored) {
            }
        }
    }

    private void debugLogSearchHits(def json) {
        def numFound = json?.response?.numFound
        def docs = json?.response?.docs
        int shown = (docs instanceof Collection) ? docs.size() : 0
        debugLog("  numFound=${numFound} shown=${shown}")
        if (docs instanceof Collection && !docs.isEmpty()) {
            docs.eachWithIndex { doc, i ->
                debugLog("  hit[${i}]: g=${doc.g} a=${doc.a} v=${doc.v ?: doc.latestVersion} id=${doc.id} p=${doc.p} versionCount=${doc.versionCount}")
            }
        } else {
            debugLog("  hit: none")
        }
    }

    private static String formatDebugLine(String message) {
        return LocalDateTime.now().format(DEBUG_LOG_TS) + "  " + (message ?: "") + "\n"
    }

    private static String stackTraceOf(Throwable e) {
        if (e == null) return ""
        StringWriter sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        return sw.toString()
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
        String versionSourceComment

        String toString() { "${groupId}:${artifactId}:${version}" }
    }
}