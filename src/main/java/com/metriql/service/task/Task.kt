package com.metriql.service.task

import com.fasterxml.jackson.annotation.JsonIgnore
import com.metriql.service.auth.ProjectAuth
import com.metriql.util.UppercaseEnum
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Task is an abstraction of Runnable,
 * @param T: Result type of the task
 * @param K: Status type of the task
 * @param type: Type of the task
 * @param projectId: Task is executed for project
 * @param userId: Initiated by user, is null if it's a system task such as dashboard update or dbt update tasks
 * @param isBackgroundTask: Is this task a background task, or user initiated?
 * */

abstract class Task<T, K>(val projectId: Int, val userId: Int?, private val isBackgroundTask: Boolean) : Runnable {
    private var id: UUID? = null

    private val startedAt = Instant.now()!!
    private var endedAt: Instant? = null
    private var scope: Instant? = null
    private var currentStatsCalledAtMillis = System.currentTimeMillis() // Sets when currentStats is called
    private var delegates = mutableListOf<(T?) -> Unit>()
    private var postProcessors = mutableListOf<(T) -> T>()

    @Volatile
    var status = Status.QUEUED
        private set

    private var result: T? = null

    // Can't reach this. inside timer schedule, this is only a proxy method
    private fun cancelTask() {
        this.cancel()
    }

    fun getId() = id

    fun setId(id: UUID) {
        if (this.id != null) {
            throw IllegalArgumentException("Task already have an id.")
        }
        this.id = id
    }

    @Synchronized
    fun setResult(result: T) {
        if (this.result == Status.FINISHED) {
            throw IllegalStateException("Task result is already set.")
        }

        var processedResult = result
        for (postProcessor in postProcessors) {
            processedResult = postProcessor.invoke(processedResult)
        }
        this.result = processedResult

        endedAt = Instant.now()
        this.status = Status.FINISHED

        for (delegate in delegates) {
            // Delegates may perform intensive works.
            // Execute callbacks on a different thread pool within non failing try? block
            try {
                delegate.invoke(result)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Error running task callback", e)
            }
        }
    }

    fun getDuration(): Duration {
        return Duration.ofSeconds((endedAt ?: Instant.now()).epochSecond - startedAt.epochSecond)
    }

    @Synchronized
    fun onFinish(action: (T?) -> Unit) {
        // Notify immediately in case the task already finished before the delegation
        if (status == Status.FINISHED) {
            try {
                action.invoke(result)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Error running task callback", e)
            }
        } else {
            delegates.add(action)
        }
    }

    // for tests.
    fun runAndWaitForResult(): T {
        run()
        return result!!
    }

    open fun cancel() {
        endedAt = Instant.now()
        this.status = Status.CANCELED

        delegates.forEach {
            try {
                it.invoke(result)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Error running task callback", e)
            }
        }
    }

    // should set currentStatsCalledAt
    private fun currentStats(): K {
        currentStatsCalledAtMillis = System.currentTimeMillis()
        return this.getStats()
    }

    protected abstract fun getStats(): K

    data class TaskTicket<T>(
        val id: UUID?,
        val startedAt: Instant,
        val status: Status,
        val update: Any?,
        val result: T?
    ) {
        @JsonIgnore
        fun isDone(): Boolean {
            return status == Status.FINISHED || status == Status.CANCELED
        }
    }

    fun taskTicket(): TaskTicket<T> {
        if (status != Status.FINISHED && id == null) {
            throw IllegalStateException("Long running tasks can't be serialized without id")
        }

        return TaskTicket(id, startedAt, status, currentStats(), result)
    }

    @UppercaseEnum
    enum class Status {
        QUEUED, RUNNING, CANCELED, FINISHED
    }

    fun markAsRunning() {
        if (status != Status.QUEUED) {
            throw IllegalStateException()
        }
        status = Status.RUNNING
    }

    fun isDone(): Boolean {
        return status == Status.FINISHED || status == Status.CANCELED
    }

    fun getLastAccessedAt(): Long? {
        return currentStatsCalledAtMillis
    }

    @Synchronized
    fun addPostProcessor(postProcessor: (T) -> T) {
        if (status == Status.FINISHED) {
            throw IllegalStateException("Can't add new post-processor while task is already finished.")
        }
        postProcessors.add(postProcessor)
    }

    companion object {
        private val logger = Logger.getLogger(this::class.java.name)

        fun <Result, Stat> completedTask(auth: ProjectAuth, result: Result, stats: Stat): Task<Result, Stat> {
            val value = object : Task<Result, Stat>(auth.projectId, auth.userId, false) {
                override fun run() {}

                override fun getStats() = stats
            }
            value.setResult(result)
            return value
        }
    }
}
