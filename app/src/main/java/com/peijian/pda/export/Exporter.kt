package com.peijian.pda.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.peijian.pda.data.AppDatabase
import com.peijian.pda.data.PackingRepository
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.logic.DateText
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 导出：
 *  1. 库存清单（与「配件记录.xlsx / 打包记录」完全相同的 17 列）+ 物料汇总
 *  2. 单张发货单的 出库明细 + 物料汇总
 *  3. 盘点差异 + 盘亏箱 + 盘盈/异常 + 扫描明细
 *
 * 文件写到 /sdcard/Peijian/导出/；无权限/ROM 限制时退到 App 专属目录。
 */
object Exporter {

    const val ROOT_NAME = "Peijian"
    const val EXPORT_SUBDIR = "导出"
    private val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    val PACKING_HEADER = listOf(
        "打包日期", "客户编码", "客户名称", "业务员", "出口国", "生产单号", "箱号",
        "长*宽*高(cm)", "体积(㎥)", "毛重(kg)", "配件编码", "英文描述", "配件名称",
        "数量", "整机型号", "打包单位", "备注"
    )
    private val PACKING_WIDTHS = listOf(12, 11, 28, 10, 10, 11, 7, 14, 10, 10, 20, 34, 26, 8, 24, 10, 22)

    data class Result(val file: File, val rows: Int, val note: String = "")

    fun exportDir(context: Context): File {
        val primary = File(Environment.getExternalStorageDirectory(), "$ROOT_NAME/$EXPORT_SUBDIR")
        if (runCatching { primary.mkdirs() || primary.isDirectory }.getOrDefault(false)) return primary
        val fallback = File(context.getExternalFilesDir(null), EXPORT_SUBDIR)
        runCatching { fallback.mkdirs() }
        return fallback
    }

    private inline fun writeWithFallback(context: Context, name: String, block: (File) -> Int): Result {
        val primary = File(exportDir(context), name)
        runCatching { return Result(primary, block(primary)) }
        val fallback = File(context.getExternalFilesDir(null), EXPORT_SUBDIR).apply { mkdirs() }
        val f = File(fallback, name)
        val rows = block(f)
        return Result(f, rows, "已写入 App 专属目录（外置存储不可写）")
    }

    /* ---------------- 库存 ---------------- */

    suspend fun exportInventory(context: Context, db: AppDatabase): Result {
        val rows = db.packingDao().inventoryRows()
        val summary = db.packingDao().summaryRows()
        val name = "库存清单_${LocalDateTime.now().format(ts)}.xlsx"

        return writeWithFallback(context, name) { file ->
            val packingRows = rows.map { r ->
                listOf(
                    PackingImporter.formatDate(r.packDate),
                    r.customerCode, r.customerName, r.salesperson, r.country,
                    r.orderNo, r.boxNo, r.dimension,
                    r.volume ?: "", r.grossWeight ?: "",
                    r.partCode, r.englishName, r.partName, r.quantity,
                    r.machineModel, r.packingUnit,
                    listOf(r.boxRemark, r.itemRemark).filter { it.isNotBlank() }.distinct().joinToString(" / ")
                )
            }
            val summaryRows = summary.map { s ->
                listOf(s.partCode, s.partName, s.englishName, s.machineModel, s.totalQty, s.boxCount, s.itemLines)
            }.toMutableList()
            summaryRows.add(
                listOf(
                    "合计", "", "", "",
                    summary.sumOf { it.totalQty },
                    rows.map { it.boxId }.distinct().size,
                    rows.size
                )
            )
            XlsxWriter.write(
                file,
                listOf(
                    XlsxWriter.Sheet("库存清单", PACKING_HEADER, packingRows, PACKING_WIDTHS),
                    XlsxWriter.Sheet(
                        "物料汇总",
                        listOf("配件编码", "配件名称", "英文描述", "整机型号", "数量合计", "箱数", "明细行数"),
                        summaryRows,
                        listOf(22, 28, 36, 26, 12, 8, 10)
                    )
                )
            )
            packingRows.size
        }
    }

    /* ---------------- 出库 ---------------- */

    suspend fun exportShipment(context: Context, db: AppDatabase, shipmentId: Long): Result {
        val shipment = db.shipmentDao().findById(shipmentId) ?: error("发货单不存在")
        val detail = db.shipmentDao().detailRows(shipmentId)
        val summary = db.shipmentDao().summaryOfShipment(shipmentId)
        val safeDoc = shipment.docNo.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "shipment" }
        val name = "出库明细_${safeDoc}_${LocalDateTime.now().format(ts)}.xlsx"

        return writeWithFallback(context, name) { file ->
            val rows = detail.map { d ->
                listOf(
                    d.docNo, DateText.dateTime(d.createdAt), d.customerCode, d.customerName,
                    d.orderNo, d.boxNo, d.barcode, d.partCode, d.partName, d.englishName,
                    d.machineModel, d.quantity, d.operator
                )
            }
            val summaryRows = summary.map { s ->
                listOf(s.partCode, s.partName, s.totalQty)
            }.toMutableList()
            summaryRows.add(listOf("合计", "", summary.sumOf { it.totalQty }))
            XlsxWriter.write(
                file,
                listOf(
                    XlsxWriter.Sheet(
                        "出库明细",
                        listOf(
                            "发货柜号", "出库时间", "客户编码", "客户名称",
                            "生产单号", "箱号", "箱码", "配件编码", "配件名称", "英文描述",
                            "整机型号", "数量", "操作员"
                        ),
                        rows,
                        listOf(16, 18, 11, 28, 11, 7, 26, 20, 26, 32, 24, 8, 10)
                    ),
                    XlsxWriter.Sheet(
                        "物料汇总",
                        listOf("配件编码", "配件名称", "数量合计"),
                        summaryRows,
                        listOf(22, 30, 12)
                    )
                )
            )
            rows.size
        }
    }

    /* ---------------- 盘点 ---------------- */

    suspend fun exportStocktake(context: Context, db: AppDatabase, checkId: Long): Result {
        val check = db.stockCheckDao().findById(checkId) ?: error("盘点批次不存在")
        val report = PackingRepository(db).stocktakeReport(checkId)
        val scans = db.stockCheckDao().scans(checkId)
        val safeBatch = check.batchNo.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "check" }
        val name = "盘点差异_${safeBatch}_${LocalDateTime.now().format(ts)}.xlsx"

        return writeWithFallback(context, name) { file ->
            val diffRows = report.rows.map { r ->
                listOf(
                    r.partCode, r.partName, r.bookQty, r.actualQty, r.diff,
                    when {
                        r.diff == 0L -> "一致"
                        r.diff > 0 -> "盘盈"
                        else -> "盘亏"
                    }
                )
            }
            val missingRows = report.missingBoxes.map { b ->
                listOf(b.barcode, b.orderNo, b.boxNo, b.customerCode, b.customerName, PackingImporter.formatDate(b.packDate))
            }
            val extraRows = scans.filter { !it.expected && it.totalQty > 0 }.map { s ->
                listOf(s.barcode, s.orderNo, s.boxNo, s.partSummary, s.totalQty, DateText.dateTime(s.scannedAt))
            }
            val unknownRows = scans.filter { !it.expected && it.totalQty <= 0 }.map { s ->
                listOf(s.barcode, DateText.dateTime(s.scannedAt))
            }
            val scanRows = scans.map { s ->
                listOf(
                    s.barcode, s.orderNo, s.boxNo, if (s.expected) "系统应存" else "异常/盘盈",
                    s.partSummary, s.totalQty, DateText.dateTime(s.scannedAt)
                )
            }

            XlsxWriter.write(
                file,
                listOf(
                    XlsxWriter.Sheet(
                        "盘点汇总",
                        listOf("项目", "数值"),
                        listOf(
                            listOf("盘点批次", check.batchNo),
                            listOf("操作员", check.operator),
                            listOf("开始时间", DateText.dateTime(check.startedAt)),
                            listOf("结束时间", check.finishedAt?.let { DateText.dateTime(it) } ?: ""),
                            listOf("系统应存箱数", report.bookedBoxes),
                            listOf("已扫箱数", report.scannedBoxes),
                            listOf("匹配箱数", report.matchedBoxes),
                            listOf("盘盈/异常箱数", report.extraBoxes),
                            listOf("未知箱码数", report.unknownScans),
                            listOf("账面总数量", report.bookQty),
                            listOf("实盘总数量", report.actualQty),
                            listOf("差异数量", report.actualQty - report.bookQty)
                        ),
                        listOf(20, 26)
                    ),
                    XlsxWriter.Sheet(
                        "盘点差异",
                        listOf("配件编码", "配件名称", "账面数量", "实盘数量", "差异", "结果"),
                        diffRows,
                        listOf(22, 30, 12, 12, 10, 10)
                    ),
                    XlsxWriter.Sheet(
                        "盘亏箱",
                        listOf("箱码", "生产单号", "箱号", "客户编码", "客户名称", "打包日期"),
                        missingRows,
                        listOf(26, 12, 8, 12, 30, 12)
                    ),
                    XlsxWriter.Sheet(
                        "盘盈_异常",
                        listOf("箱码", "生产单号", "箱号", "配件明细", "数量", "扫描时间"),
                        extraRows,
                        listOf(26, 12, 8, 40, 10, 20)
                    ),
                    XlsxWriter.Sheet(
                        "未知箱码",
                        listOf("扫码内容", "扫描时间"),
                        unknownRows,
                        listOf(36, 20)
                    ),
                    XlsxWriter.Sheet(
                        "扫描明细",
                        listOf("箱码", "生产单号", "箱号", "系统状态", "配件明细", "数量", "扫描时间"),
                        scanRows,
                        listOf(26, 12, 8, 12, 40, 10, 20)
                    )
                )
            )
            scans.size
        }
    }

    /* ---------------- 分享 ---------------- */

    fun share(context: Context, file: File) {
        shareAs(context, file, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }

    /** 按指定 MIME 分享任意文件（备份 zip 等）。 */
    fun shareAs(context: Context, file: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "发送文件"))
    }

    fun openFolderHint(context: Context): String = exportDir(context).absolutePath
}
