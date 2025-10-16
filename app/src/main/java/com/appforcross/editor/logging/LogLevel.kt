package com.appforcross.editor.logging

/** Уровни структурного логирования. */
enum class LogLevel(val weight: Int) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40);
    fun allows(other: LogLevel) = other.weight >= this.weight
}