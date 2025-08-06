package com.posifro

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.*
import javax.swing.table.DefaultTableModel
import kotlin.concurrent.thread
import kotlin.io.path.name

/**
 * ファイルを監視して表示するアプリケーション
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

    private fun stopWatching() {
        running = false
        watchThread?.interrupt()
        watchService?.close()
        watchThread = null
        watchService = null
    }
    
    private fun startWatching() {
        // 既存の監視を停止
        if (watchThread != null) {
            stopWatching()
        }
        
        running = true
        watchThread = thread {
            try {
                watchService = FileSystems.getDefault().newWatchService()
                watchPath.register(
                    watchService,
                    ENTRY_CREATE,
                    ENTRY_DELETE,
                    ENTRY_MODIFY
                )
                
                while (running) {
                    val key = try {
                        watchService?.take() ?: break
                    } catch (e: InterruptedException) {
                        break
                    }
                    
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        
                        if (kind == OVERFLOW) {
                            continue
                        }
                        
                        // ファイルリストを更新
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

fun main() {
    SwingUtilities.invokeLater {
        FileWatcherApp().isVisible = true
    }
}