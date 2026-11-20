package com.peijian.pda.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 发货前「预选箱」：从在库箱里提前勾选本次要发的箱，形成白名单。
 * 扫码出库时，若箱不在本单预选清单里则拒绝（防止发错货）。
 *
 * 选择留空表示「不启用预选限制」，此时按原有逻辑任意在库箱都可扫。
 */
@Entity(
    tableName = "shipment_plan_box",
    indices = [
        Index(value = ["shipmentId", "boxId"], unique = true),
        Index("shipmentId"),
        Index("boxId")
    ]
)
data class ShipmentPlanBox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shipmentId: Long,
    val boxId: Long,
    /** 冗余展示字段，避免列表每次回查 packing_box */
    val barcode: String,
    val orderNo: String,
    val boxNo: String,
    val customerCode: String,
    val customerName: String,
    val partSummary: String = "",
    val totalQty: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
)
