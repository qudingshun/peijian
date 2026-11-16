package com.peijian.pda.importer

/** 从 Excel 或电脑端同步得到的「一箱」数据（尚未落库） */
data class BoxPayload(
    val customerCode: String,
    val customerName: String,
    val salesperson: String,
    val country: String,
    val orderNo: String,
    val boxNo: String,
    val packDate: Long?,
    val dimension: String,
    val volume: Double?,
    val grossWeight: Double?,
    val packingUnit: String,
    val remark: String,
    val items: List<ItemPayload>,
    val rev: Long = 0,
    val sourceRow: Int = 0,
    /** 服务端箱的当前状态（IN_STOCK / SHIPPED / VOID），旧版本接口缺省为在库 */
    val status: String = "IN_STOCK"
)

data class ItemPayload(
    val partCode: String,
    val englishName: String,
    val partName: String,
    val quantity: Int,
    val machineModel: String,
    val remark: String
)

data class ImportResult(
    val boxes: Int,
    val items: Int,
    val inserted: Int,
    val updated: Int,
    val duplicates: Int,
    val skippedRows: Int,
    val sheetName: String = "",
    val durationMs: Long = 0
)
