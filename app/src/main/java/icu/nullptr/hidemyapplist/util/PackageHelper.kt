package icu.nullptr.hidemyapplist.util

import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.os.IUserManager
import android.os.ServiceManager
import icu.nullptr.hidemyapplist.common.BinderWrapper
import icu.nullptr.hidemyapplist.common.Constants
import icu.nullptr.hidemyapplist.hmaApp
import icu.nullptr.hidemyapplist.service.PrefManager
import icu.nullptr.hidemyapplist.service.ServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import java.text.Collator
import java.util.Locale

object PackageHelper {

    class PackageCache(
        val info: PackageInfo,
        val label: String,
        val icon: Bitmap
    )

    private object Comparators {
        val byLabel = Comparator<String> { o1, o2 ->
            val n1 = loadAppLabel(o1).lowercase(Locale.getDefault())
            val n2 = loadAppLabel(o2).lowercase(Locale.getDefault())
            Collator.getInstance(Locale.getDefault()).compare(n1, n2)
        }
        val byPackageName = Comparator<String> { o1, o2 ->
            val n1 = o1.lowercase(Locale.getDefault())
            val n2 = o2.lowercase(Locale.getDefault())
            Collator.getInstance(Locale.getDefault()).compare(n1, n2)
        }
        val byInstallTime = Comparator<String> { o1, o2 ->
            val n1 = loadPackageInfo(o1).firstInstallTime
            val n2 = loadPackageInfo(o2).firstInstallTime
            n2.compareTo(n1)
        }
        val byUpdateTime = Comparator<String> { o1, o2 ->
            val n1 = loadPackageInfo(o1).lastUpdateTime
            val n2 = loadPackageInfo(o2).lastUpdateTime
            n2.compareTo(n1)
        }
    }

    private val ipm: IPackageManager by lazy {
        IPackageManager.Stub.asInterface(
            BinderWrapper(ServiceManager.getService("package"), ServiceClient.asBinder()!!)
        )
    }
    private val ium: IUserManager by lazy {
        IUserManager.Stub.asInterface(
            BinderWrapper(ServiceManager.getService("user"), ServiceClient.asBinder()!!)
        )
    }
    private val pm = hmaApp.packageManager

    private val packageCache = MutableSharedFlow<Map<String, PackageCache>>(replay = 1)
    private val mAppList = MutableSharedFlow<MutableList<String>>(replay = 1)
    val appList: SharedFlow<List<String>> = mAppList

    private val mRefreshing = MutableSharedFlow<Boolean>(replay = 1)
    val isRefreshing: SharedFlow<Boolean> = mRefreshing

    init {
        invalidateCache()
    }

    fun invalidateCache() {
        hmaApp.globalScope.launch {
            mRefreshing.emit(true)
            val cache = withContext(Dispatchers.IO) {
                val packageMap = mutableMapOf<String, PackageCache>()
                for (userId in UserManagerApis.getUserIdsNoThrow()) {
                    val packages = PackageManagerApis.getInstalledPackagesNoThrow(0, userId)
                    packages.forEach { packageInfo ->
                        if (packageInfo.packageName in Constants.packagesShouldNotHide) return@forEach
                        packageMap.computeIfAbsent(packageInfo.packageName) {
                            packageInfo.applicationInfo.uid %= 100000
                            val label =
                                pm.getApplicationLabel(packageInfo.applicationInfo).toString()
                            val icon = hmaApp.appIconLoader.loadIcon(packageInfo.applicationInfo)
                            PackageCache(packageInfo, label, icon)
                        }
                    }
                }
                packageMap
            }
            packageCache.emit(cache)
            mAppList.emit(cache.keys.toMutableList())
            mRefreshing.emit(false)
        }
    }

    suspend fun sortList(firstComparator: Comparator<String>) {
        var comparator = when (PrefManager.appFilter_sortMethod) {
            PrefManager.SortMethod.BY_LABEL -> Comparators.byLabel
            PrefManager.SortMethod.BY_PACKAGE_NAME -> Comparators.byPackageName
            PrefManager.SortMethod.BY_INSTALL_TIME -> Comparators.byInstallTime
            PrefManager.SortMethod.BY_UPDATE_TIME -> Comparators.byUpdateTime
        }
        if (PrefManager.appFilter_reverseOrder) comparator = comparator.reversed()
        val list = mAppList.first()
        list.sortWith(firstComparator.then(comparator))
        mAppList.emit(list)
    }

    fun loadPackageInfo(packageName: String): PackageInfo = runBlocking {
        packageCache.first()[packageName]!!.info
    }

    fun loadAppLabel(packageName: String): String = runBlocking {
        packageCache.first()[packageName]!!.label
    }

    fun loadAppIcon(packageName: String): Bitmap = runBlocking {
        packageCache.first()[packageName]!!.icon
    }

    fun isSystem(packageName: String): Boolean = runBlocking {
        packageCache.first()[packageName]!!.info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }
}
