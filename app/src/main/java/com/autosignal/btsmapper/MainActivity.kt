package com.autosignal.btsmapper

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.telephony.*
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

// Thông số sóng đọc từ chip thu (Serving & Neighbor)
data class SignalReading(
    val isServing: Boolean,
    val rat: String,
    val mcc: Int,
    val mnc: Int,
    val tac: Int,
    val eci: Long,         // > 0 nếu đọc được Cell ID đầy đủ, -1 nếu Neighbor bị ẩn ECI
    val eNodeB: Long,
    val sectorId: Int,
    val pci: Int,
    val earfcn: Int,
    val rsrp: Int,
    val rsrq: Int,
    val ta: Int
)

// Thông tin Cột & Cánh sóng đã giải mã
data class ResolvedSector(
    val eci: Long,
    val eNodeB: Long,
    val sectorId: Int,
    val pci: Int,
    val earfcn: Int,
    val towerLat: Double,
    val towerLon: Double,
    val azimuth: Double,
    val beamWidth: Double = 65.0,
    val radiusMeters: Double = 380.0,
    val source: String,
    val siblingAzimuths: List<Double> = emptyList(),
    val maxRsrp: Int = -140,
    val bestLat: Double = 0.0,
    val bestLon: Double = 0.0
)

// ============================================================================
// CƠ SỞ DỮ LIỆU SQLITE OFFLINE & TỰ HỌC THỰC ĐỊA
// ============================================================================
class BtsDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "autobts_offline.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE cells (
                cache_key TEXT PRIMARY KEY,
                mcc INTEGER,
                mnc INTEGER,
                tac INTEGER,
                eci INTEGER,
                enodeb INTEGER,
                sector_id INTEGER,
                pci INTEGER,
                earfcn INTEGER,
                tower_lat REAL,
                tower_lon REAL,
                azimuth REAL,
                source TEXT,
                siblings TEXT,
                max_rsrp INTEGER,
                best_lat REAL,
                best_lon REAL,
                updated_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_pci_earfcn ON cells(pci, earfcn)")
        db.execSQL("CREATE INDEX idx_eci ON cells(eci)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cells")
        onCreate(db)
    }

    fun getByKey(key: String): ResolvedSector? {
        val db = readableDatabase
        db.rawQuery("SELECT * FROM cells WHERE cache_key = ? LIMIT 1", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return cursorToSector(c)
        }
        return null
    }

    fun getByEci(eci: Long): ResolvedSector? {
        if (eci <= 0) return null
        val db = readableDatabase
        db.rawQuery("SELECT * FROM cells WHERE eci = ? LIMIT 1", arrayOf(eci.toString())).use { c ->
            if (c.moveToFirst()) return cursorToSector(c)
        }
        return null
    }

    // Tìm trạm Neighbor chỉ có PCI + EARFCN bằng cách đối chiếu các trạm đã lưu quanh vị trí đứng (< 5km)
    fun findNeighborByPci(pci: Int, earfcn: Int, userLat: Double, userLon: Double): ResolvedSector? {
        if (pci < 0) return null
        val db = readableDatabase
        val query = if (earfcn > 0) {
            "SELECT * FROM cells WHERE pci = ? AND earfcn = ?"
        } else {
            "SELECT * FROM cells WHERE pci = ?"
        }
        val args = if (earfcn > 0) arrayOf(pci.toString(), earfcn.toString()) else arrayOf(pci.toString())

        var closest: ResolvedSector? = null
        var minDistance = 5000.0 // Bán kính khớp tối đa 5km

        db.rawQuery(query, args).use { c ->
            while (c.moveToNext()) {
                val sec = cursorToSector(c)
                val results = FloatArray(1)
                Location.distanceBetween(userLat, userLon, sec.towerLat, sec.towerLon, results)
                if (results[0] < minDistance) {
                    minDistance = results[0].toDouble()
                    closest = sec
                }
            }
        }
        return closest
    }

    // Lưu hoặc tự hiệu chỉnh góc quạt khi đo được sóng mạnh hơn ngoài thực địa
    fun saveOrCalibrate(r: SignalReading, sector: ResolvedSector, userGps: GeoPoint?): ResolvedSector {
        if (r.eci <= 0) return sector
        val key = "${r.mcc}-${r.mnc}-${r.tac}-${r.eci}"
        val existing = getByKey(key)

        var finalLat = sector.towerLat
        var finalLon = sector.towerLon
        var finalAzimuth = sector.azimuth
        var finalSource = sector.source
        var maxRsrp = existing?.maxRsrp ?: -140
        var bestLat = existing?.bestLat ?: (userGps?.latitude ?: 0.0)
        var bestLon = existing?.bestLon ?: (userGps?.longitude ?: 0.0)

        // Nếu phát hiện mức sóng thực địa mạnh hơn kỷ lục cũ -> Cập nhật tâm sóng mạnh nhất
        if (userGps != null && r.rsrp > maxRsrp) {
            maxRsrp = r.rsrp
            bestLat = userGps.latitude
            bestLon = userGps.longitude

            // Tự học: Nếu sóng rất mạnh (>= -72 dBm) và cách chân cột > 25m, hiệu chỉnh lại góc cánh sóng hướng về điểm này
            val dist = FloatArray(1)
            Location.distanceBetween(finalLat, finalLon, bestLat, bestLon, dist)
            if (r.rsrp >= -72 && dist[0] in 25f..600f) {
                val phi1 = Math.toRadians(finalLat)
                val phi2 = Math.toRadians(bestLat)
                val dLon = Math.toRadians(bestLon - finalLon)
                val y = sin(dLon) * cos(phi2)
                val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
                val learnedAz = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
                finalAzimuth = learnedAz
                if (!finalSource.contains("Đã hiệu chỉnh")) {
                    finalSource = "$finalSource [Đã hiệu chỉnh]"
                }
            }
        }

        val updated = sector.copy(
            towerLat = finalLat,
            towerLon = finalLon,
            azimuth = finalAzimuth,
            source = finalSource,
            maxRsrp = maxRsrp,
            bestLat = bestLat,
            bestLon = bestLon
        )

        val cv = ContentValues().apply {
            put("cache_key", key)
            put("mcc", r.mcc)
            put("mnc", r.mnc)
            put("tac", r.tac)
            put("eci", r.eci)
            put("enodeb", r.eNodeB)
            put("sector_id", r.sectorId)
            put("pci", r.pci)
            put("earfcn", r.earfcn)
            put("tower_lat", updated.towerLat)
            put("tower_lon", updated.towerLon)
            put("azimuth", updated.azimuth)
            put("source", updated.source)
            put("siblings", updated.siblingAzimuths.joinToString(","))
            put("max_rsrp", updated.maxRsrp)
            put("best_lat", updated.bestLat)
            put("best_lon", updated.bestLon)
            put("updated_at", System.currentTimeMillis())
        }

        writableDatabase.insertWithOnConflict("cells", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return updated
    }

    fun countSavedCells(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM cells", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun cursorToSector(c: android.database.Cursor): ResolvedSector {
        val sibStr = c.getString(c.getColumnIndexOrThrow("siblings")) ?: ""
        val sibList = if (sibStr.isBlank()) emptyList() else sibStr.split(",").mapNotNull { it.toDoubleOrNull() }
        return ResolvedSector(
            eci = c.getLong(c.getColumnIndexOrThrow("eci")),
            eNodeB = c.getLong(c.getColumnIndexOrThrow("enodeb")),
            sectorId = c.getInt(c.getColumnIndexOrThrow("sector_id")),
            pci = c.getInt(c.getColumnIndexOrThrow("pci")),
            earfcn = c.getInt(c.getColumnIndexOrThrow("earfcn")),
            towerLat = c.getDouble(c.getColumnIndexOrThrow("tower_lat")),
            towerLon = c.getDouble(c.getColumnIndexOrThrow("tower_lon")),
            azimuth = c.getDouble(c.getColumnIndexOrThrow("azimuth")),
            source = c.getString(c.getColumnIndexOrThrow("source")) ?: "SQLite Offline",
            siblingAzimuths = sibList,
            maxRsrp = c.getInt(c.getColumnIndexOrThrow("max_rsrp")),
            bestLat = c.getDouble(c.getColumnIndexOrThrow("best_lat")),
            bestLon = c.getDouble(c.getColumnIndexOrThrow("best_lon"))
        )
    }
}

// ============================================================================
// MAIN ACTIVITY (BẢN V2.0: QUÉT NEIGHBOR + KHÓA MỤC TIÊU + ÂM BÁO + SQLITE)
// ============================================================================
class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var tvHud: TextView
    private lateinit var btnLockTarget: Button
    private lateinit var btnToggleAudio: Button
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: BtsDatabaseHelper

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val rawApiCoordCache = ConcurrentHashMap<String, Pair<Double, Double>>()

    private var userLocation: GeoPoint? = null
    private var autoFollowGps = true
    private var lastTrailPoint: GeoPoint? = null

    // Trạng thái Khóa mục tiêu (Target Lock) & Âm báo Geiger
    @Volatile private var lockedEci: Long? = null
    @Volatile private var lockedPci: Int? = null
    @Volatile private var lockedTargetCurrentRsrp: Int? = null
    @Volatile private var audioPingEnabled = true
    private var toneGenerator: ToneGenerator? = null

    // Danh sách các trạm vừa quét được để chọn nhanh trong hộp thoại Khóa
    private var lastScannedCells: List<SignalReading> = emptyList()

    // Các lớp hiển thị trên bản đồ
    private var userMarker: Marker? = null
    private var servingTowerMarker: Marker? = null
    private var servingSectorPolygon: Polygon? = null
    private var servingLinkLine: Polyline? = null
    private val siblingPolygons = mutableListOf<Polygon>()
    private val neighborOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
    private var targetLockLine: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = packageName

        dbHelper = BtsDatabaseHelper(this)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) {}

        setupUserInterface()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        checkAndRequestPermissions()
    }

    private fun setupUserInterface() {
        val rootLayout = FrameLayout(this)

        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(20.8449, 106.6881))
        }
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Bảng HUD hiển thị Trạm chính + Mục tiêu khóa + Danh sách Neighbor Cells
        tvHud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101622"))
                cornerRadius = 22f
                setStroke(2, Color.parseColor("#00E5FF"))
            }
            text = "Đang khởi tạo AutoBTS v2.0..."
        }
        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            setMargins(20, 32, 20, 0)
        }
        rootLayout.addView(tvHud, hudParams)

        // Cụm nút điều khiển chuyên dụng bên phải
        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        btnLockTarget = makeButton("🎯 Khóa Cell/PCI", "#D81B60") {
            showTargetLockDialog()
        }

        btnToggleAudio = makeButton("🔊 Âm báo: BẬT", "#6A1B9A") {
            audioPingEnabled = !audioPingEnabled
            btnToggleAudio.text = if (audioPingEnabled) "🔊 Âm báo: BẬT" else "🔇 Âm báo: TẮT"
        }

        val btnCenter = makeButton("📍 Bám GPS: BẬT", "#00897B") {
            autoFollowGps = !autoFollowGps
            (it as Button).text = if (autoFollowGps) "📍 Bám GPS: BẬT" else "📍 Bám GPS: TẮT"
            userLocation?.let { loc -> if (autoFollowGps) mapView.controller.animateTo(loc) }
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 10, 0, 0) }

        btnContainer.addView(btnLockTarget, btnParams)
        btnContainer.addView(btnToggleAudio, btnParams)
        btnContainer.addView(btnCenter, btnParams)

        val containerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(20, 20, 20, 50)
        }
        rootLayout.addView(btnContainer, containerParams)

        setContentView(rootLayout)
    }

    private fun makeButton(title: String, hexColor: String, onClick: ( android.view.View) -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(26, 14, 26, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 20f
            }
            setOnClickListener(onClick)
        }
    }

    // ============================================================================
    // HỘP THOẠI KHÓA MỤC TIÊU (CHỌN TỪ SÓNG ĐANG BẮT ĐƯỢC HOẶC NHẬP TAY ECI/PCI)
    // ============================================================================
    private fun showTargetLockDialog() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (lockedEci != null || lockedPci != null) {
            options.add("❌ HỦY KHÓA MỤC TIÊU HIỆN TẠI")
            actions.add {
                lockedEci = null
                lockedPci = null
                lockedTargetCurrentRsrp = null
                btnLockTarget.text = "🎯 Khóa Cell/PCI"
                Toast.makeText(this, "Đã mở khóa mục tiêu", Toast.LENGTH_SHORT).show()
            }
        }

        // Đưa danh sách Serving + Neighbor đang quét được vào menu chọn nhanh
        for (cell in lastScannedCells) {
            val tag = if (cell.isServing) "[CHÍNH]" else "[LÂN CẬN]"
            val label = if (cell.eci > 0) {
                "$tag eNB:${cell.eNodeB}-Cánh:${cell.sectorId} | PCI:${cell.pci} (${cell.rsrp} dBm)"
            } else {
                "$tag PCI:${cell.pci} | EARFCN:${cell.earfcn} (${cell.rsrp} dBm)"
            }
            options.add(label)
            actions.add {
                lockedEci = if (cell.eci > 0) cell.eci else null
                lockedPci = if (cell.pci >= 0) cell.pci else null
                btnLockTarget.text = "🎯 Đang khóa: ${lockedEci ?: "PCI ${lockedPci}"}"
                Toast.makeText(this, "Đã khóa mục tiêu! Âm báo sẽ kêu nhanh dần khi lại gần.", Toast.LENGTH_SHORT).show()
            }
        }

        options.add("⌨️ Tự nhập mã ECI hoặc PCI cần săn...")
        actions.add { showManualInputLockDialog() }

        AlertDialog.Builder(this)
            .setTitle("🎯 Chọn Cánh Sóng Mục Tiêu Để Khóa")
            .setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showManualInputLockDialog() {
        val input = EditText(this).apply {
            hint = "Nhập ECI (VD: 256001) hoặc PCI (0 - 503)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập mã ECI hoặc PCI mục tiêu")
            .setView(input)
            .setPositiveButton("Khóa ngay") { _, _ ->
                val v = input.text.toString().trim().toLongOrNull()
                if (v != null) {
                    if (v <= 503L) {
                        lockedPci = v.toInt()
                        lockedEci = null
                        btnLockTarget.text = "🎯 Khóa PCI: $v"
                    } else {
                        lockedEci = v
                        lockedPci = null
                        btnLockTarget.text = "🎯 Khóa ECI: $v"
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startEngines()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startEngines()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startEngines() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, this)
            val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastLoc?.let { onLocationChanged(it) }
        } catch (_: Exception) {}

        // Luồng 1: Quét trạm chính + trạm lân cận mỗi 2 giây
        mainScope.launch {
            while (isActive) {
                scanServingAndNeighbors()
                delay(2000L)
            }
        }

        // Luồng 2: Bộ phát nhịp âm báo Geiger (tự động thay đổi tốc độ bíp theo RSRP của mục tiêu khóa)
        mainScope.launch(Dispatchers.Default) {
            while (isActive) {
                val targetRsrp = lockedTargetCurrentRsrp
                if ((lockedEci != null || lockedPci != null) && audioPingEnabled && targetRsrp != null) {
                    val (tone, durationMs, intervalMs) = when {
                        targetRsrp >= -70 -> Triple(ToneGenerator.TONE_CDMA_HIGH_L, 90, 220L)
                        targetRsrp >= -80 -> Triple(ToneGenerator.TONE_CDMA_HIGH_L, 80, 400L)
                        targetRsrp >= -90 -> Triple(ToneGenerator.TONE_PROP_BEEP, 70, 700L)
                        targetRsrp >= -100 -> Triple(ToneGenerator.TONE_PROP_BEEP, 60, 1100L)
                        else -> Triple(ToneGenerator.TONE_PROP_BEEP, 50, 1600L)
                    }
                    try {
                        toneGenerator?.startTone(tone, durationMs)
                    } catch (_: Exception) {}
                    delay(intervalMs)
                } else {
                    delay(400L)
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val pt = GeoPoint(location.latitude, location.longitude)
        userLocation = pt
        if (autoFollowGps) mapView.controller.animateTo(pt)
        updateUserMarker(pt)
    }

    // ============================================================================
    // QUÉT ĐỒNG THỜI TRẠM CHÍNH (SERVING) & TRẠM LÂN CẬN (NEIGHBORS)
    // ============================================================================
    @SuppressLint("MissingPermission")
    private suspend fun scanServingAndNeighbors() {
        val rawCells = try { telephonyManager.allCellInfo } catch (_: Exception) { null } ?: return
        val parsedAll = rawCells.mapNotNull { parseCellInfo(it) }
        if (parsedAll.isEmpty()) return

        lastScannedCells = parsedAll

        val serving = parsedAll.firstOrNull { it.isServing } ?: parsedAll.first()
        val neighbors = parsedAll.filter { it != serving }.sortedByDescending { it.rsrp }.take(5)

        val currentGps = userLocation
        if (currentGps != null) {
            drawSignalTrailPoint(currentGps, serving.rsrp)
        }

        // Kiểm tra xem mục tiêu đang khóa có xuất hiện trong Serving hay Neighbor không
        val matchedTargetReading = parsedAll.firstOrNull { cell ->
            (lockedEci != null && cell.eci == lockedEci) ||
            (lockedPci != null && cell.pci == lockedPci && cell.pci >= 0)
        }
        lockedTargetCurrentRsrp = matchedTargetReading?.rsrp

        // Giải mã vị trí trạm chính + Lưu & tự hiệu chỉnh vào SQLite Offline
        val resolvedServing = withContext(Dispatchers.IO) {
            resolveCellWithOfflineDb(serving, currentGps)
        }

        // Giải mã các trạm Neighbor (qua ECI hoặc khớp PCI+EARFCN trong SQLite Offline)
        val resolvedNeighbors = withContext(Dispatchers.IO) {
            neighbors.map { nb -> Pair(nb, resolveNeighborCell(nb, currentGps)) }
        }

        // Nếu đang khóa 1 ECI cụ thể đã có trong SQLite, lấy tọa độ cột mục tiêu để kẻ đường dẫn hướng
        val lockedSectorRecord = withContext(Dispatchers.IO) {
            lockedEci?.let { dbHelper.getByEci(it) }
                ?: if (lockedPci != null && currentGps != null) {
                    dbHelper.findNeighborByPci(lockedPci!!, -1, currentGps.latitude, currentGps.longitude)
                } else null
        }

        val totalDbCount = withContext(Dispatchers.IO) { dbHelper.countSavedCells() }

        renderMapAndHud(serving, resolvedServing, resolvedNeighbors, matchedTargetReading, lockedSectorRecord, totalDbCount)
    }

    private fun parseCellInfo(info: CellInfo): SignalReading? {
        val isReg = info.isRegistered
        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                val rawCi = id.ci
                val validEci = if (rawCi > 0 && rawCi != Int.MAX_VALUE) rawCi.toLong() else -1L
                val pci = if (id.pci in 0..503) id.pci else -1
                if (validEci <= 0 && pci < 0) return null

                val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString?.toIntOrNull() ?: 452 else 452
                val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString?.toIntOrNull() ?: 4 else 4

                SignalReading(
                    isServing = isReg,
                    rat = "4G",
                    mcc = mcc,
                    mnc = mnc,
                    tac = if (id.tac != Int.MAX_VALUE && id.tac > 0) id.tac else 0,
                    eci = validEci,
                    eNodeB = if (validEci > 0) validEci shr 8 else -1L,
                    sectorId = if (validEci > 0) (validEci and 0xFF).toInt() else -1,
                    pci = pci,
                    earfcn = if (id.earfcn != Int.MAX_VALUE) id.earfcn else -1,
                    rsrp = if (sig.rsrp in -140..-40) sig.rsrp else sig.dbm,
                    rsrq = if (sig.rsrq in -30..0) sig.rsrq else 0,
                    ta = if (sig.timingAdvance in 0..1282) sig.timingAdvance else -1
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                val validCid = if (id.cid > 0 && id.cid != Int.MAX_VALUE) id.cid.toLong() else -1L
                val psc = if (id.psc in 0..511) id.psc else -1
                if (validCid <= 0 && psc < 0) return null
                val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString?.toIntOrNull() ?: 452 else 452
                val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString?.toIntOrNull() ?: 4 else 4

                SignalReading(
                    isServing = isReg,
                    rat = "3G",
                    mcc = mcc,
                    mnc = mnc,
                    tac = if (id.lac != Int.MAX_VALUE) id.lac else 0,
                    eci = validCid,
                    eNodeB = if (validCid > 0) validCid shr 16 else -1L,
                    sectorId = if (validCid > 0) (validCid and 0xFFFF).toInt() % 10 else -1,
                    pci = psc,
                    earfcn = if (id.uarfcn != Int.MAX_VALUE) id.uarfcn else -1,
                    rsrp = sig.dbm,
                    rsrq = 0,
                    ta = -1
                )
            }
            else -> null
        }
    }

    // Giải mã trạm: Ưu tiên SQLite Offline -> Truy vấn API Internet đa nguồn -> Tự học hiệu chỉnh
    private fun resolveCellWithOfflineDb(r: SignalReading, userGps: GeoPoint?): ResolvedSector? {
        if (r.eci <= 0) return null
        val key = "${r.mcc}-${r.mnc}-${r.tac}-${r.eci}"

        // 1. Kiểm tra trong SQLite Offline trước
        val cachedInDb = dbHelper.getByKey(key)
        if (cachedInDb != null) {
            return dbHelper.saveOrCalibrate(r, cachedInDb, userGps)
        }

        // 2. Chưa có trong SQLite -> Gọi Internet (Mylnikov + BeaconDB) & Tam giác đạc các cánh cùng eNodeB
        val currentCoord = fetchCellCoordinateFromInternet(r.mcc, r.mnc, r.tac, r.eci)
        val siblingCoords = mutableMapOf<Int, Pair<Double, Double>>()
        if (currentCoord != null) siblingCoords[r.sectorId] = currentCoord

        if (r.rat == "4G" && r.sectorId >= 0) {
            for (sibSec in getSiblingSectorIds(r.sectorId)) {
                val sibEci = (r.eNodeB shl 8) or sibSec.toLong()
                val coord = fetchCellCoordinateFromInternet(r.mcc, r.mnc, r.tac, sibEci)
                if (coord != null) siblingCoords[sibSec] = coord
            }
        }

        if (siblingCoords.size >= 2 && siblingCoords.containsKey(r.sectorId)) {
            val tLat = siblingCoords.values.map { it.first }.average()
            val tLon = siblingCoords.values.map { it.second }.average()
            val thisPt = siblingCoords[r.sectorId]!!
            val az = if (distanceMeters(tLat, tLon, thisPt.first, thisPt.second) >= 15.0) {
                calculateBearing(tLat, tLon, thisPt.first, thisPt.second)
            } else estimateStandardAzimuth(r.sectorId)

            val otherAz = siblingCoords.filterKeys { it != r.sectorId }.map { (secId, pt) ->
                if (distanceMeters(tLat, tLon, pt.first, pt.second) >= 15.0) {
                    calculateBearing(tLat, tLon, pt.first, pt.second)
                } else estimateStandardAzimuth(secId)
            }

            val resolved = ResolvedSector(
                eci = r.eci, eNodeB = r.eNodeB, sectorId = r.sectorId, pci = r.pci, earfcn = r.earfcn,
                towerLat = tLat, towerLon = tLon, azimuth = az,
                source = "Hội tụ ${siblingCoords.size} cánh eNB",
                siblingAzimuths = otherAz
            )
            return dbHelper.saveOrCalibrate(r, resolved, userGps)
        }

        if (currentCoord != null) {
            val (lat, lon) = currentCoord
            val az = if (userGps != null && distanceMeters(lat, lon, userGps.latitude, userGps.longitude) > 20.0) {
                calculateBearing(lat, lon, userGps.latitude, userGps.longitude)
            } else estimateStandardAzimuth(r.sectorId)

            val resolved = ResolvedSector(
                eci = r.eci, eNodeB = r.eNodeB, sectorId = r.sectorId, pci = r.pci, earfcn = r.earfcn,
                towerLat = lat, towerLon = lon, azimuth = az,
                source = "Cloud DB",
                siblingAzimuths = listOf((az + 120.0) % 360.0, (az + 240.0) % 360.0)
            )
            return dbHelper.saveOrCalibrate(r, resolved, userGps)
        }

        // 3. Nội suy thực địa khi gặp trạm mới chưa có trên Internet
        if (userGps != null) {
            val estAz = estimateStandardAzimuth(r.sectorId)
            val revBrg = (estAz + 180.0) % 360.0
            val estDist = when {
                r.ta in 1..40 -> r.ta * 78.12
                r.rsrp >= -72 -> 120.0
                r.rsrp >= -85 -> 260.0
                else -> 450.0
            }
            val estTower = destinationPoint(userGps.latitude, userGps.longitude, estDist, revBrg)
            val resolved = ResolvedSector(
                eci = r.eci, eNodeB = r.eNodeB, sectorId = r.sectorId, pci = r.pci, earfcn = r.earfcn,
                towerLat = estTower.latitude, towerLon = estTower.longitude, azimuth = estAz,
                source = "Nội suy TA/RSRP",
                siblingAzimuths = listOf((estAz + 120.0) % 360.0, (estAz + 240.0) % 360.0)
            )
            return dbHelper.saveOrCalibrate(r, resolved, userGps)
        }

        return null
    }

    // Giải mã trạm Neighbor: Nếu có ECI thì tra bình thường, nếu ẩn ECI thì dò theo PCI + EARFCN trong SQLite
    private fun resolveNeighborCell(nb: SignalReading, userGps: GeoPoint?): ResolvedSector? {
        if (nb.eci > 0) {
            return resolveCellWithOfflineDb(nb, userGps)
        }
        if (userGps != null && nb.pci >= 0) {
            return dbHelper.findNeighborByPci(nb.pci, nb.earfcn, userGps.latitude, userGps.longitude)
        }
        return null
    }

    private fun fetchCellCoordinateFromInternet(mcc: Int, mnc: Int, tac: Int, eci: Long): Pair<Double, Double>? {
        val key = "$mcc-$mnc-$tac-$eci"
        rawApiCoordCache[key]?.let { return it }

        try {
            val urlStr = "https://api.mylnikov.org/geolocation/cell?v=1.1&data=open&mcc=$mcc&mnc=$mnc&lac=$tac&cellid=$eci"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (json.optInt("result") == 200) {
                    val data = json.getJSONObject("data")
                    val pair = Pair(data.getDouble("lat"), data.getDouble("lon"))
                    rawApiCoordCache[key] = pair
                    return pair
                }
            }
        } catch (_: Exception) {}

        try {
            val conn = (URL("https://api.beacondb.net/v1/geolocate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "AutoBTSMapper/2.0")
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
            }
            val payload = JSONObject().apply {
                put("cellTowers", JSONArray().put(JSONObject().apply {
                    put("radioType", "lte")
                    put("mobileCountryCode", mcc)
                    put("mobileNetworkCode", mnc)
                    put("locationAreaCode", tac)
                    put("cellId", eci)
                }))
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val loc = json.optJSONObject("location")
                if (loc != null) {
                    val pair = Pair(loc.getDouble("lat"), loc.getDouble("lng"))
                    rawApiCoordCache[key] = pair
                    return pair
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun getSiblingSectorIds(sectorId: Int): List<Int> {
        val base = (sectorId / 10) * 10
        val mod = sectorId % 10
        return when (mod) {
            in 1..3 -> listOf(base + 1, base + 2, base + 3).filter { it != sectorId }
            in 4..6 -> listOf(base + 4, base + 5, base + 6).filter { it != sectorId }
            else -> emptyList()
        }
    }

    private fun estimateStandardAzimuth(sectorId: Int): Double {
        return when (sectorId % 10) {
            1, 4, 7 -> 30.0
            2, 5, 8 -> 150.0
            3, 6, 9 -> 270.0
            else -> 0.0
        }
    }

    // ============================================================================
    // HIỂN THỊ HUD & VẼ ĐỒNG THỜI TRẠM CHÍNH + NEIGHBOR CELLS + TIA KHÓA MỤC TIÊU
    // ============================================================================
    private fun renderMapAndHud(
        serving: SignalReading,
        resolvedServing: ResolvedSector?,
        neighbors: List<Pair<SignalReading, ResolvedSector?>>,
        matchedTargetReading: SignalReading?,
        lockedSectorRecord: ResolvedSector?,
        totalDbCount: Int
    ) {
        val carrier = when (serving.mnc) {
            4 -> "Viettel"
            2 -> "VinaPhone"
            1 -> "MobiFone"
            else -> "MNC ${serving.mnc}"
        }

        val distStr = if (resolvedServing != null && userLocation != null) {
            "${distanceMeters(resolvedServing.towerLat, resolvedServing.towerLon, userLocation!!.latitude, userLocation!!.longitude).roundToInt()}m"
        } else "--"

        tvHud.text = buildString {
            append("📡 $carrier (${serving.rat}) | eNB: ${serving.eNodeB} - Cánh: ${serving.sectorId} | PCI: ${serving.pci}\n")
            append("📶 RSRP: ${serving.rsrp} dBm | RSRQ: ${serving.rsrq} dB | Cách cột: $distStr\n")
            if (resolvedServing != null) {
                append("🧭 Góc cánh: ${resolvedServing.azimuth.roundToInt()}° | DB Offline: $totalDbCount cánh (${resolvedServing.source})\n")
            }

            // Hiển thị trạng thái Khóa Mục Tiêu (Target Lock)
            if (lockedEci != null || lockedPci != null) {
                val targetName = if (lockedEci != null) "ECI:$lockedEci" else "PCI:$lockedPci"
                if (matchedTargetReading != null) {
                    val role = if (matchedTargetReading.isServing) "ĐANG KẾT NỐI CHÍNH" else "TRẠM LÂN CẬN"
                    append("🎯 KHÓA [$targetName]: ${matchedTargetReading.rsrp} dBm ($role)\n")
                } else {
                    append("🎯 KHÓA [$targetName]: Đang ngoài vùng phủ sóng...\n")
                }
            }

            // Hiển thị danh sách Neighbor Cells
            if (neighbors.isNotEmpty()) {
                append("🔍 Lân cận (${neighbors.size}): ")
                append(neighbors.joinToString(" | ") { (nb, res) ->
                    val tag = if (res != null) "eNB:${res.eNodeB}-${res.sectorId}" else "PCI:${nb.pci}"
                    "$tag(${nb.rsrp})"
                })
            }
        }

        // Xóa các lớp Neighbor cũ trước khi vẽ lượt mới
        neighborOverlays.forEach { mapView.overlays.remove(it) }
        neighborOverlays.clear()

        // 1. Vẽ các cánh sóng Neighbor đã giải mã được tọa độ (Màu tím bán trong suốt)
        for ((nb, res) in neighbors) {
            if (res != null) {
                val nbCenter = GeoPoint(res.towerLat, res.towerLon)
                val nbPoly = Polygon().apply {
                    points = createSectorPolygonPoints(nbCenter, res.azimuth, res.beamWidth, res.radiusMeters * 0.85)
                    fillPaint.color = Color.argb(55, 170, 0, 255)
                    outlinePaint.color = Color.parseColor("#E040FB")
                    outlinePaint.strokeWidth = 2.5f
                    title = "Neighbor eNB:${res.eNodeB}-Cánh:${res.sectorId} (${nb.rsrp} dBm)"
                }
                mapView.overlays.add(nbPoly)
                neighborOverlays.add(nbPoly)

                userLocation?.let { uPt ->
                    val nbLine = Polyline().apply {
                        setPoints(listOf(nbCenter, uPt))
                        outlinePaint.color = Color.argb(130, 224, 64, 251)
                        outlinePaint.strokeWidth = 2.5f
                    }
                    mapView.overlays.add(nbLine)
                    neighborOverlays.add(nbLine)
                }
            }
        }

        // 2. Vẽ Trạm Chính (Serving Cell)
        if (resolvedServing != null) {
            val towerGeo = GeoPoint(resolvedServing.towerLat, resolvedServing.towerLon)

            if (servingTowerMarker == null) {
                servingTowerMarker = Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(servingTowerMarker)
            }
            servingTowerMarker?.apply {
                position = towerGeo
                title = "Trạm eNodeB: ${serving.eNodeB} (Cánh ${serving.sectorId})"
                snippet = "Góc: ${resolvedServing.azimuth.roundToInt()}° | Đỉnh sóng: ${resolvedServing.maxRsrp} dBm"
            }

            siblingPolygons.forEach { mapView.overlays.remove(it) }
            siblingPolygons.clear()
            for (sibAz in resolvedServing.siblingAzimuths) {
                val sibPoly = Polygon().apply {
                    points = createSectorPolygonPoints(towerGeo, sibAz, resolvedServing.beamWidth, resolvedServing.radiusMeters * 0.8)
                    fillPaint.color = Color.argb(40, 144, 164, 174)
                    outlinePaint.color = Color.argb(110, 144, 164, 174)
                    outlinePaint.strokeWidth = 2f
                }
                siblingPolygons.add(sibPoly)
                mapView.overlays.add(sibPoly)
            }

            if (servingSectorPolygon == null) {
                servingSectorPolygon = Polygon()
                mapView.overlays.add(servingSectorPolygon)
            }
            val sigColor = getSignalColor(serving.rsrp)
            servingSectorPolygon?.apply {
                points = createSectorPolygonPoints(towerGeo, resolvedServing.azimuth, resolvedServing.beamWidth, resolvedServing.radiusMeters)
                fillPaint.color = Color.argb(95, Color.red(sigColor), Color.green(sigColor), Color.blue(sigColor))
                outlinePaint.color = sigColor
                outlinePaint.strokeWidth = 4.5f
                title = "Cánh chính #${serving.sectorId} (${serving.rsrp} dBm)"
            }

            userLocation?.let { uPt ->
                if (servingLinkLine == null) {
                    servingLinkLine = Polyline().apply { outlinePaint.strokeWidth = 4f }
                    mapView.overlays.add(servingLinkLine)
                }
                servingLinkLine?.apply {
                    setPoints(listOf(towerGeo, uPt))
                    outlinePaint.color = sigColor
                }
            }
        }

        // 3. Nếu đang Khóa Mục Tiêu và biết tọa độ cột mục tiêu -> Vẽ tia dẫn đường màu Đỏ Hồng nổi bật
        if (lockedSectorRecord != null && userLocation != null) {
            if (targetLockLine == null) {
                targetLockLine = Polyline().apply {
                    outlinePaint.color = Color.parseColor("#FF1744")
                    outlinePaint.strokeWidth = 6.5f
                }
                mapView.overlays.add(targetLockLine)
            }
            targetLockLine?.setPoints(listOf(
                GeoPoint(lockedSectorRecord.towerLat, lockedSectorRecord.towerLon),
                userLocation!!
            ))
        } else {
            targetLockLine?.let { mapView.overlays.remove(it) }
            targetLockLine = null
        }

        mapView.invalidate()
    }

    private fun updateUserMarker(pt: GeoPoint) {
        if (userMarker == null) {
            userMarker = Marker(mapView).apply {
                title = "Vị trí máy đo"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(userMarker)
        }
        userMarker?.position = pt
        mapView.invalidate()
    }

    private fun drawSignalTrailPoint(pt: GeoPoint, rsrp: Int) {
        val last = lastTrailPoint
        if (last != null && distanceMeters(last.latitude, last.longitude, pt.latitude, pt.longitude) < 8.0) return
        lastTrailPoint = pt

        val dot = Polygon().apply {
            points = Polygon.pointsAsCircle(pt, 5.5)
            fillPaint.color = getSignalColor(rsrp)
            outlinePaint.color = Color.BLACK
            outlinePaint.strokeWidth = 1f
        }
        mapView.overlays.add(0, dot)
    }

    private fun createSectorPolygonPoints(center: GeoPoint, azimuth: Double, beamWidth: Double, radiusMeters: Double): List<GeoPoint> {
        val pts = mutableListOf(center)
        val startAngle = azimuth - (beamWidth / 2.0)
        val endAngle = azimuth + (beamWidth / 2.0)
        val steps = 18
        val stepAngle = (endAngle - startAngle) / steps
        for (i in 0..steps) {
            val angle = (startAngle + i * stepAngle + 360.0) % 360.0
            pts.add(destinationPoint(center.latitude, center.longitude, radiusMeters, angle))
        }
        pts.add(center)
        return pts
    }

    private fun destinationPoint(lat: Double, lon: Double, distMeters: Double, bearingDeg: Double): GeoPoint {
        val earthRadius = 6371000.0
        val angDist = distMeters / earthRadius
        val brg = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(brg))
        val lon2 = lon1 + atan2(sin(brg) * sin(angDist) * cos(lat1), cos(angDist) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun getSignalColor(rsrp: Int): Int {
        return when {
            rsrp >= -80 -> Color.parseColor("#00E676")
            rsrp >= -90 -> Color.parseColor("#76FF03")
            rsrp >= -100 -> Color.parseColor("#FFEA00")
            rsrp >= -110 -> Color.parseColor("#FF9100")
            else -> Color.parseColor("#FF1744")
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        locationManager.removeUpdates(this)
        toneGenerator?.release()
    }
}
