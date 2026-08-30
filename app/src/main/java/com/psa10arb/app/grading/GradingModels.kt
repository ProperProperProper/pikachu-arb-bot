package com.psa10arb.app.grading

/** Ported from grading_engine.py's InspPoint dataclass. */
data class InspPoint(
    val code: String,
    val section: String,
    val label: String,
    val score: Double,
    val flag: Boolean,
    val detail: String,
)

/** Ported from grading_engine.py's GradingResult dataclass. */
data class GradingResult(
    val centeringGrade: Int = 0,
    val cornerGrade: Int = 0,
    val edgeGrade: Int = 0,
    val probableGrade: Int = 0,
    val limitingFactor: String = "",
    val lrRatio: String = "",
    val tbRatio: String = "",
    val worstCornerPct: Double = 0.0,
    val worstCornerPos: String = "",
    val edgeDefects: Int = 0,
    val isHolo: Boolean = false,
    val holoConfidence: String = "none",
    val surfaceFlagged: Boolean = false,
    val remediation: List<String> = emptyList(),
    val surfaceGrade: Int = 0,
    val hasScratch: Boolean = false,
    val hasCrease: Boolean = false,
    val borderL: Double = 0.0,
    val borderR: Double = 0.0,
    val borderT: Double = 0.0,
    val borderB: Double = 0.0,
    val inspPoints: List<InspPoint> = emptyList(),
    val cornerWhitening: Map<String, Double> = emptyMap(),
    val cornerSharpness: Map<String, Double> = emptyMap(),
    val edgeZoneFlags: Map<String, Boolean> = emptyMap(),
    val lrPcts: Pair<Double, Double> = 50.0 to 50.0,
    val tbPcts: Pair<Double, Double> = 50.0 to 50.0,
    val photoMode: Boolean = false,
)
