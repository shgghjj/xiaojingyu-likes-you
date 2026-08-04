package com.pockettavern.app.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalToolExecutorInstrumentedTest {

    @Test
    fun createReadEditAppendAndDeleteTextFileThroughMediaStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = LocalToolExecutor(context)
        val fileName = "月语真机测试_${System.currentTimeMillis()}.txt"

        try {
            val created = executor.execute(LocalToolAction.CreateFile(fileName, "第一行"))
            assertTrue(created, created.contains("已创建文件"))
            assertTrue(executor.execute(LocalToolAction.ReadFile(fileName)).contains("第一行"))
            assertTrue(executor.execute(LocalToolAction.EditFile(fileName, "第二行")).contains("已修改文件"))
            assertTrue(executor.execute(LocalToolAction.ReadFile(fileName)).contains("第二行"))
            assertTrue(executor.execute(LocalToolAction.EditFile(fileName, "\n第三行", append = true)).contains("已向"))
            val appended = executor.execute(LocalToolAction.ReadFile(fileName))
            assertTrue(appended.contains("第二行"))
            assertTrue(appended.contains("第三行"))
        } finally {
            executor.execute(LocalToolAction.DeleteFile(fileName))
        }
    }
}
