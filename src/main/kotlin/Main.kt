/**
 * ファイル監視アプリケーション
 * 
 * 指定されたディレクトリ内のファイル変更を監視し、変更があった場合にOSネイティブの通知を表示します。
 * Windows と Mac の両方の環境で動作します。
 * 
 * 機能:
 * - ファイルの作成、削除、変更を検出して通知
 * - 監視対象フォルダの変更
 * - OSネイティブ通知システムを使用（Windows/Mac）
 * - システムトレイ通知へのフォールバック
 * - 最終手段としてダイアログ通知
 */
package com.posifro

import java.awt.*
import java.awt.TrayIcon.MessageType
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.*
import javax.swing.table.DefaultTableModel
import kotlin.concurrent.thread
import kotlin.io.path.name

/**
 * ファイルを監視して表示し、変更があった場合に通知するアプリケーション
 * Windows と Mac の両方の環境で動作します
 */
class FileWatcherApp : JFrame("ファイル監視") {
    private val fileTableModel = DefaultTableModel().apply {
        addColumn("ファイル名")
        addColumn("タイプ")
        addColumn("サイズ")
        addColumn("最終更新")
    }
    private val fileTable = JTable(fileTableModel)
    private val statusLabel = JLabel("監視中...")
    private var watchPath = Paths.get(System.getProperty("user.home"), "Desktop")
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private var running = true
    private val notificationSystem = NotificationSystem()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        size = Dimension(800, 600)
        layout = BorderLayout()

        // メニューバーの設定
        setupMenuBar()

        // テーブルをスクロール可能に
        val scrollPane = JScrollPane(fileTable)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        // 初期タイトルの設定
        title = "ファイル監視 - ${watchPath}"

        // 初期ファイル一覧を表示
        refreshFileList()

        // ウィンドウが閉じられたときの処理
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                stopWatching()
            }
        })

        // ファイル監視を開始
        startWatching()
    }
    
    private fun setupMenuBar() {
        val menuBar = JMenuBar()
        
        // ファイルメニュー
        val fileMenu = JMenu("ファイル")
        val exitItem = JMenuItem("終了")
        exitItem.addActionListener {
            stopWatching()
            dispose()
        }
        fileMenu.add(exitItem)
        
        // 設定メニュー
        val settingsMenu = JMenu("設定")
        val changeFolderItem = JMenuItem("監視フォルダの変更")
        changeFolderItem.addActionListener {
            selectFolder()
        }
        settingsMenu.add(changeFolderItem)
        
        menuBar.add(fileMenu)
        menuBar.add(settingsMenu)
        jMenuBar = menuBar
    }
    
    private fun selectFolder() {
        val fileChooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "監視するフォルダを選択"
            currentDirectory = watchPath.toFile()
        }
        
        val result = fileChooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            val selectedFile = fileChooser.selectedFile
            changeWatchFolder(selectedFile.toPath())
        }
    }

    private fun refreshFileList() {
        fileTableModel.rowCount = 0
        try {
            Files.list(watchPath).forEach { path ->
                val file = path.toFile()
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                val fileType = if (file.isDirectory) "フォルダ" else "ファイル"
                val fileSize = if (file.isDirectory) "-" else "${attrs.size()} bytes"
                val lastModified = attrs.lastModifiedTime().toString().replace("T", " ").substring(0, 19)
                
                SwingUtilities.invokeLater {
                    fileTableModel.addRow(arrayOf(path.name, fileType, fileSize, lastModified))
                }
            }
            statusLabel.text = "最終更新: ${java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19)}"
        } catch (e: Exception) {
            statusLabel.text = "エラー: ${e.message}"
        }
    }

    /**
     * ファイル監視を停止し、通知システムのリソースをクリーンアップする
     */
    private fun stopWatching() {
        running = false
        watchThread?.interrupt()
        watchService?.close()
        watchThread = null
        watchService = null
        notificationSystem.cleanup()
    }
    
    /**
     * ファイル監視を開始し、変更があった場合に通知を表示する
     * 監視対象のイベント: ファイルの作成、削除、変更
     * macOSでの信頼性を向上させるための特別な処理を含む
     */
    private fun startWatching() {
        // 既存の監視を停止
        if (watchThread != null) {
            stopWatching()
        }
        
        running = true
        watchThread = thread {
            try {
                // macOSかどうかを確認
                val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
                
                watchService = FileSystems.getDefault().newWatchService()
                
                // 標準的な登録
                watchPath.register(
                    watchService,
                    ENTRY_CREATE,
                    ENTRY_DELETE,
                    ENTRY_MODIFY
                )
                
                // macOS用の待機時間（ミリ秒）
                val waitTime = if (isMacOS) 200L else 0L

                while (running) {
                    val key = try {
                        if (isMacOS) {
                            // macOSではポーリング方式を使用
                            val pollResult = watchService?.poll()

                            // キーがなければ少し待機してループ継続
                            if (pollResult == null) {
                                Thread.sleep(waitTime)
                                continue
                            }
                            pollResult
                        } else {
                            // 他のOSでは標準的なブロッキング方式
                            watchService?.take() ?: break
                        }
                    } catch (e: InterruptedException) {
                        break
                    }

                    // イベントが発生したかどうかを追跡
                    var eventOccurred = false

                    for (event in key.pollEvents()) {
                        val kind = event.kind()

                        if (kind == OVERFLOW) {
                            continue
                        }

                        // イベントが発生したことを記録
                        eventOccurred = true

                        // イベントの詳細を取得
                        val watchEvent = event as WatchEvent<Path>
                        val filename = watchEvent.context().toString()

                        // イベントタイプに応じた通知を表示
                        when (kind) {
                            ENTRY_CREATE -> {
                                SwingUtilities.invokeLater { 
                                    notificationSystem.showNotification(
                                        "ファイル作成",
                                        "新しいファイルが作成されました: $filename",
                                        MessageType.INFO
                                    )
                                }
                            }
                            ENTRY_DELETE -> {
                                SwingUtilities.invokeLater { 
                                    notificationSystem.showNotification(
                                        "ファイル削除",
                                        "ファイルが削除されました: $filename",
                                        MessageType.WARNING
                                    )
                                }
                            }
                            ENTRY_MODIFY -> {
                                SwingUtilities.invokeLater { 
                                    notificationSystem.showNotification(
                                        "ファイル変更",
                                        "ファイルが変更されました: $filename",
                                        MessageType.INFO
                                    )
                                }
                            }
                        }
                    }

                    // イベントが発生した場合のみファイルリストを更新
                    if (eventOccurred) {
                        SwingUtilities.invokeLater { refreshFileList() }
                    }
                    
                    if (!key.reset()) {
                        break
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "監視エラー: ${e.message}"
                }
            }
        }
    }
    
    private fun changeWatchFolder(newPath: Path) {
        if (Files.isDirectory(newPath)) {
            watchPath = newPath
            title = "ファイル監視 - ${watchPath}"
            statusLabel.text = "フォルダを変更しました: ${watchPath}"
            startWatching()
            refreshFileList()
        } else {
            JOptionPane.showMessageDialog(
                this,
                "有効なフォルダを選択してください",
                "エラー",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
}

/**
 * プラットフォーム固有の通知を処理するインターフェース
 */
interface PlatformNotifier {
    fun showNotification(title: String, message: String, type: MessageType)
    fun cleanup()
}

/**
 * Windows用の通知実装
 */
class WindowsNotifier : PlatformNotifier {
    override fun showNotification(title: String, message: String, type: MessageType) {
        try {
            // Windows用の通知 - PowerShellスクリプトを使用
            val escapedTitle = title.replace("\"", "\\\"")
            val escapedMessage = message.replace("\"", "\\\"")
            
            // PowerShellスクリプトを一時ファイルに書き出す
            val tempFile = File.createTempFile("notification", ".ps1")
            tempFile.writeText("""
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] | Out-Null
                [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime] | Out-Null
                [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType=WindowsRuntime] | Out-Null
                
                ${'$'}template = [Windows.UI.Notifications.ToastTemplateType]::ToastText02
                ${'$'}xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent(${'$'}template)
                ${'$'}xml.GetElementsByTagName('text')[0].AppendChild(${'$'}xml.CreateTextNode("$escapedTitle"))
                ${'$'}xml.GetElementsByTagName('text')[1].AppendChild(${'$'}xml.CreateTextNode("$escapedMessage"))
                
                ${'$'}toast = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("com.posifro.FileWatcher")
                ${'$'}notification = New-Object Windows.UI.Notifications.ToastNotification(${'$'}xml)
                ${'$'}toast.Show(${'$'}notification)
            """.trimIndent())
            
            // PowerShellスクリプトを実行
            val process = Runtime.getRuntime().exec(arrayOf("powershell", "-ExecutionPolicy", "Bypass", "-File", tempFile.absolutePath))
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            
            // 一時ファイルを削除
            tempFile.delete()
        } catch (e: Exception) {
            println("Windows通知の表示に失敗しました: ${e.message}")
            // 代替手段としてmsgコマンドを試す
            try {
                Runtime.getRuntime().exec(arrayOf("msg", "*", "$title: $message"))
            } catch (e2: Exception) {
                // 最終的にトレイ通知にフォールバック
                fallbackToTrayNotification(title, message, type)
            }
        }
    }
    
    override fun cleanup() {
        // 特に必要なクリーンアップはない
    }
}

/**
 * Mac用の通知実装
 * 
 * AppleScriptを使用してMacのネイティブ通知を表示します。
 * macOSの権限とセキュリティに対応した改良版
 */
class MacNotifier : PlatformNotifier {
    override fun showNotification(title: String, message: String, type: MessageType) {
        try {
            // まずAppleScriptの権限を確認
            checkAppleScriptPermission()

            // AppleScriptを使用してネイティブ通知を表示
            val escapedTitle = title.replace("\"", "\\\"").replace("'", "\\'")
            val escapedMessage = message.replace("\"", "\\\"").replace("'", "\\'")

            // より確実な通知方法
            val script = """
                try
                    display notification "$escapedMessage" with title "$escapedTitle" sound name "Glass"
                on error
                    tell application "System Events"
                        display notification "$escapedMessage" with title "$escapedTitle"
                    end tell
                end try
            """.trimIndent()

            val process = ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()

            // プロセスの出力を取得
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            println("AppleScript実行結果: exitCode=$exitCode, output=$output")
        } catch (e: Exception) {
            println("通知に失敗しました: ${e.message}")
        }
    }
    
    /**
     * AppleScriptの権限を確認
     */
    private fun checkAppleScriptPermission() {
        try {
            val testScript = "tell application \"System Events\" to get name"
            val process = ProcessBuilder("osascript", "-e", testScript)
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                println("AppleScriptの権限が不足している可能性があります")
                println("システム環境設定 > セキュリティとプライバシー > プライバシー > オートメーション で権限を確認してください")
            }
        } catch (e: Exception) {
            println("AppleScript権限の確認に失敗しました: ${e.message}")
        }
    }
    

    
    override fun cleanup() {
        // 特に必要なクリーンアップはない
    }
}

/**
 * システム通知を管理するクラス
 * 
 * OSに応じたネイティブ通知を表示します。Windows、Mac OSXに対応しています。
 * 通知に失敗した場合は、システムトレイ通知、最終的にはダイアログにフォールバックします。
 */
class NotificationSystem {
    private var trayIcon: TrayIcon? = null
    private var systemTray: SystemTray? = null
    private val platformNotifier: PlatformNotifier
    
    init {
        setupTrayIcon()
        platformNotifier = when {
            System.getProperty("os.name").lowercase().contains("win") -> WindowsNotifier()
            System.getProperty("os.name").lowercase().contains("mac") -> MacNotifier()
            else -> object : PlatformNotifier {
                override fun showNotification(title: String, message: String, type: MessageType) {
                    fallbackToTrayNotification(title, message, type)
                }
                override fun cleanup() {}
            }
        }
    }
    
    /**
     * リソースをクリーンアップ
     */
    fun cleanup() {
        platformNotifier.cleanup()
        if (SystemTray.isSupported() && trayIcon != null) {
            try {
                systemTray?.remove(trayIcon)
                trayIcon = null
            } catch (e: Exception) {
                println("トレイアイコンの削除に失敗しました: ${e.message}")
            }
        }
    }
    
    /**
     * システムトレイアイコンをセットアップ
     */
    private fun setupTrayIcon() {
        if (SystemTray.isSupported()) {
            systemTray = SystemTray.getSystemTray()
            
            // 既存のトレイアイコンがあれば削除
            if (trayIcon != null) {
                try {
                    systemTray?.remove(trayIcon)
                } catch (e: Exception) {
                    println("トレイアイコンの削除に失敗しました: ${e.message}")
                }
            }
            
            val image = Toolkit.getDefaultToolkit().createImage(javaClass.getResource("/icon.png"))
                ?: createDefaultImage()
            
            trayIcon = TrayIcon(image, "ファイル監視").apply {
                isImageAutoSize = true
            }
            
            try {
                systemTray?.add(trayIcon)
            } catch (e: AWTException) {
                println("システムトレイにアイコンを追加できませんでした: ${e.message}")
            }
        }
    }
    
    /**
     * デフォルトのアイコン画像を作成
     */
    private fun createDefaultImage(): Image {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        g2d.color = Color.WHITE
        g2d.fillRect(0, 0, size, size)
        g2d.color = Color.BLUE
        g2d.drawRect(0, 0, size - 1, size - 1)
        g2d.dispose()
        return image
    }
    
    /**
     * 通知を表示
     */
    fun showNotification(title: String, message: String, type: MessageType = MessageType.INFO) {
        // プラットフォーム固有の通知を使用
        platformNotifier.showNotification(title, message, type)
    }
}

/**
 * システムトレイ通知へのフォールバック
 */
fun fallbackToTrayNotification(title: String, message: String, type: MessageType) {
    if (SystemTray.isSupported()) {
        try {
            val systemTray = SystemTray.getSystemTray()
            val image = Toolkit.getDefaultToolkit().createImage(NotificationSystem::class.java.getResource("/icon.png"))
                ?: createDefaultFallbackImage()
            
            val tempTrayIcon = TrayIcon(image, "ファイル監視").apply {
                isImageAutoSize = true
            }
            
            systemTray.add(tempTrayIcon)
            tempTrayIcon.displayMessage(title, message, type)
            
            // 一定時間後にトレイアイコンを削除
            Thread {
                Thread.sleep(5000)
                systemTray.remove(tempTrayIcon)
            }.start()
        } catch (e: Exception) {
            println("トレイ通知の表示に失敗しました: ${e.message}")
            showDialogNotification(title, message, type)
        }
    } else {
        showDialogNotification(title, message, type)
    }
}

/**
 * ダイアログ通知へのフォールバック
 */
fun showDialogNotification(title: String, message: String, type: MessageType) {
    SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(
            null, 
            message, 
            title, 
            when(type) {
                MessageType.ERROR -> JOptionPane.ERROR_MESSAGE
                MessageType.WARNING -> JOptionPane.WARNING_MESSAGE
                else -> JOptionPane.INFORMATION_MESSAGE
            }
        )
    }
}

/**
 * フォールバック用のデフォルトアイコン画像を作成
 */
fun createDefaultFallbackImage(): Image {
    val size = 16
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g2d = image.createGraphics()
    g2d.color = Color.WHITE
    g2d.fillRect(0, 0, size, size)
    g2d.color = Color.BLUE
    g2d.drawRect(0, 0, size - 1, size - 1)
    g2d.dispose()
    return image
}

fun main() {
    SwingUtilities.invokeLater {
        FileWatcherApp().isVisible = true
    }
}