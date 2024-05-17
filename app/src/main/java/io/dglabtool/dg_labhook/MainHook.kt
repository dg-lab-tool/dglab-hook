package io.dglabtool.dg_labhook

import android.app.AndroidAppHelper
import android.content.Context
import android.util.Base64
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream


class DataWriter(private val context: Context, private val accid: String) {
    private val fileName: String
    private val file: File
    private var timestamp: Long = System.currentTimeMillis()

    init {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        fileName = "${accid}_$timestamp.txt"

        // 获取外部存储目录
        val externalDir = File(context.getExternalFilesDir(null), "dglab-record")
        if (!externalDir.exists()) {
            externalDir.mkdirs()  // 创建目录
        }

        Toast.makeText(context, "$externalDir/$fileName", Toast.LENGTH_LONG).show()

        // 获取文件对象
        file = File(externalDir, fileName)
        if (!file.exists()) {
            file.createNewFile()  // 创建文件
        }
    }

    fun writeData(serviceUuid: String, characteristicUuid: String, hexData: ByteArray) {
        val currentTimestamp = System.currentTimeMillis()
        val elapsedTime = currentTimestamp - timestamp
        timestamp = currentTimestamp


        val formattedServiceUuid = formatUuid(serviceUuid)
        val formattedCharacteristicUuid = formatUuid(characteristicUuid)

        val compressedData = compressData(hexData)
        val base64CompressedData = Base64.encodeToString(compressedData, Base64.NO_WRAP)

        val dataLine = "$elapsedTime|$formattedServiceUuid|$formattedCharacteristicUuid|$base64CompressedData\n"

        // 使用FileOutputStream以追加模式写入文件
        FileOutputStream(file, true).use { output ->
            output.write(dataLine.toByteArray())
        }
    }

    private fun formatUuid(uuid: String): String {
        val lowerCaseUuid = uuid.lowercase()

        return when {
            lowerCaseUuid.matches(Regex("955a[0-9a-f]{4}-0fe2-f5aa-a094-84b8d4f3e8ad")) -> {
                lowerCaseUuid.substring(0, 8)
            }
            lowerCaseUuid.matches(Regex("0000[0-9a-f]{4}-0000-1000-8000-00805f9b34fb")) -> {
                lowerCaseUuid.substring(0, 8)
            }
            else -> {
                uuid
            }
        }
    }

    private fun compressData(data: ByteArray): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
            gzipOutputStream.write(data)
        }
        return byteArrayOutputStream.toByteArray()
    }
}

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 过滤不必要的应用
        if (lpparam.packageName != "com.bjsm.dungeonlabs") return
        // 执行Hook
        XposedBridge.log("加载 App:"  + lpparam.packageName);
        XposedHelpers.findAndHookMethod("com.stub.StubApp", lpparam.classLoader, "a",
            Context::class.java, object : XC_MethodHook() {
                //或者a
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param) //获取到360的Context对象，通过这个对象来获取classloader
                    val context =
                        param.args[0] as Context //获取360的classloader，之后hook加固后的代码就使用这个classloader
                    val classLoader = context.classLoader //替换classloader,hook加固后的真正代码
                    hook(classLoader)
                }
            })
    }

    private fun hook(classLoader: ClassLoader) {
        var dataWriter : DataWriter? = null
        XposedHelpers.findAndHookMethod("com.blankj.utilcode.util.t", classLoader, "j",
            String::class.java,
            Array<Any>::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = AndroidAppHelper.currentApplication().applicationContext
                    val str = param.args[0] as String
                    val objArr = param.args[1] as Array<*>
                    if (str == "remoteMsg" && objArr.size == 1 && objArr[0] is String){
                        val message = objArr[0] as String
                        if (message.startsWith("有人进群了")){
                            val regex = "accid = ([a-fA-F0-9]+)".toRegex()
                            val matchResult = regex.find(message)
                            if (matchResult != null) {
                                val accid = matchResult.groupValues[1]
                                Toast.makeText(context, accid, Toast.LENGTH_SHORT).show()
                                dataWriter = DataWriter(context,accid)
                                XposedBridge.log("远程控制已连接 $accid")
                            }
                        }
                    }
                    if (str=="remoteTest"&& objArr.size == 1 && objArr[0]is String){
                        val message = objArr[0] as String
                        if (message == "对方离开房间了"){
                            dataWriter = null
                            Toast.makeText(context, "远程控制断开连接", Toast.LENGTH_SHORT).show()
                            XposedBridge.log("远程控制断开连接")
                        }
                    }
                    super.beforeHookedMethod(param)
                }

                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                }
            })
        try {
            val deviceManagerClass = XposedHelpers.findClass("com.bjsm.base.device.blemanager.DeviceManager", classLoader)
            XposedHelpers.findAndHookMethod(
                deviceManagerClass,
                "write",
                "com.bjsm.base.device.blemanager.DeviceBean",
                String::class.java,
                String::class.java,
                ByteArray::class.java,
                String::class.java,
                "kotlin.jvm.functions.Function2",
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val r9 = param.args[1] as String
                        val charact = param.args[2] as String
                        val data = param.args[3] as ByteArray
//                        XposedBridge.log("DeviceManager.write is called: device=$device, r9=$r9, charact=$charact, data=${data.contentToString()}, tag=$tag, todo=$todo")
                        dataWriter?.writeData(r9,charact,data)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Hooking failed: ${e.message}")
        }

    }
}