package com.autocamera.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat

/** 系统相机拍到新照片时被唤醒，自动另存一份加滤镜和水印的照片。 */
class PhotoJobService : JobService() {

    companion object {
        private const val JOB_ID = 4201

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, PhotoJobService::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1500)
                .setTriggerContentMaxDelay(6000)
                .build()
            js.schedule(info)
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** 找出「上次处理之后」系统相机文件夹里的新照片并处理，返回处理张数。 */
        fun processNew(ctx: Context): Int {
            val prefs = HelperPrefs(ctx)
            val since = prefs.autoSince
            val cols = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) { cols.add(MediaStore.MediaColumns.RELATIVE_PATH); cols.add(MediaStore.MediaColumns.IS_PENDING) }
            val found = mutableListOf<Pair<Long, Long>>()
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2) ?: ""
                    if (name.contains("_AC.")) continue
                    if (Build.VERSION.SDK_INT >= 29) {
                        val path = c.getString(3) ?: ""
                        if (!path.startsWith("DCIM/Camera", true)) continue
                        if (c.getInt(4) != 0) continue
                    }
                    found.add(c.getLong(0) to c.getLong(1))
                }
            }
            if (found.isEmpty()) return 0
            val model = LensDetector.deviceNames(ctx).first
            var n = 0
            for ((id, added) in found) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                try { if (PhotoProcessor.process(ctx, uri, prefs, model) != null) n++ } catch (_: Throwable) { }
                prefs.autoSince = maxOf(prefs.autoSince, added)
            }
            return n
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val n = try { processNew(this) } catch (_: Throwable) { 0 }
            if (n > 0) notifyDone(n)
            if (HelperPrefs(this).auto) schedule(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private fun notifyDone(n: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("auto", "自动处理", NotificationManager.IMPORTANCE_LOW))
        val notif = NotificationCompat.Builder(this, "auto")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("已自动处理 $n 张照片")
            .setContentText("加好滤镜和水印的照片在相册的 AutoCamera 文件夹")
            .setAutoCancel(true)
            .build()
        try { nm.notify(4202, notif) } catch (_: SecurityException) { }
    }
}
