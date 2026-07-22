package com.noteapp.benchmark

data class BenchmarkMetric(
    val name: String,
    val value: Double,
    val unit: String,
    val runtime: String,
    val delegate: String,
)

