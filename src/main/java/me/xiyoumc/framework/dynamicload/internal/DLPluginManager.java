/*
 * Copyright (C) 2014 singwhatiwanna(任玉刚) <singwhatiwanna@gmail.com>
 *
 * collaborator:田啸,宋思宇,Mr.Simple
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiyoumc.framework.dynamicload.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;

import dalvik.system.DexClassLoader;
import me.xiyoumc.framework.BasePluginActivity;
import me.xiyoumc.framework.BasePluginFragmentActivity;
import me.xiyoumc.framework.BasePluginService;
import me.xiyoumc.framework.dynamicload.DLProxyActivity;
import me.xiyoumc.framework.dynamicload.DLProxyFragmentActivity;
import me.xiyoumc.framework.dynamicload.DLProxyService;
import me.xiyoumc.framework.dynamicload.utils.DLConstants;
import me.xiyoumc.framework.dynamicload.utils.SoLibManager;

public class DLPluginManager {

    /**
     * return value of {@link #startPluginActivity(Activity, DLIntent)} start
     * success
     */
    public static final int START_RESULT_SUCCESS = 0;
    /**
     * return value of {@link #startPluginActivity(Activity, DLIntent)} package
     * not found
     */
    public static final int START_RESULT_NO_PKG = 1;
    /**
     * return value of {@link #startPluginActivity(Activity, DLIntent)} class
     * not found
     */
    public static final int START_RESULT_NO_CLASS = 2;
    /**
     * return value of {@link #startPluginActivity(Activity, DLIntent)} class
     * type error
     */
    public static final int START_RESULT_TYPE_ERROR = 3;
    private static final String TAG = "DLPluginManager";
    private static DLPluginManager sInstance;
    private final HashMap<String, DLPluginPackage> mPackagesHolder = new HashMap<String, DLPluginPackage>();
    private Context mContext;
    private int mFrom = DLConstants.FROM_INTERNAL;

    private String mNativeLibDir = null;

    private int mResult;
    private String dexOutputPath;

    private DLPluginManager(Context context) {
        mContext = context.getApplicationContext();
        mNativeLibDir = mContext.getDir("pluginlib", Context.MODE_PRIVATE).getAbsolutePath();
    }

    public static DLPluginManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (DLPluginManager.class) {
                if (sInstance == null) {
                    sInstance = new DLPluginManager(context);
                }
            }
        }

        return sInstance;
    }

    /**
     * Load a apk. Before start a plugin Activity, we should do this first.<br/>
     * NOTE : will only be called by host apk.
     */
    public DLPluginPackage loadApk(String dexPath) {
        // when loadApk is called by host apk, we assume that plugin is invoked
        // by host.
        return loadApk(dexPath, true);
    }

    /**
     * @param dexPath  plugin path
     * @param hasSoLib whether exist so lib in plugin
     */
    public DLPluginPackage loadApk(final String dexPath, boolean hasSoLib) {
        mFrom = DLConstants.FROM_EXTERNAL;

        PackageInfo packageInfo = mContext.getPackageManager().getPackageArchiveInfo(dexPath,
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
        if (packageInfo == null) {
            return null;
        }

        DLPluginPackage pluginPackage = preparePluginEnv(packageInfo, dexPath);
        if (hasSoLib) {
            copySoLib(dexPath);
        }

        return pluginPackage;
    }

    /**
     * prepare plugin runtime env, has DexClassLoader, Resources, and so on.
     */
    private DLPluginPackage preparePluginEnv(PackageInfo packageInfo, String dexPath) {

        DLPluginPackage pluginPackage = mPackagesHolder.get(packageInfo.packageName);
        if (pluginPackage != null) {
            return pluginPackage;
        }
        DexClassLoader dexClassLoader = createDexClassLoader(dexPath);
        AssetManager assetManager = createAssetManager(dexPath);
        Resources resources = createResources(assetManager);
        // create pluginPackage
        pluginPackage = new DLPluginPackage(dexClassLoader, resources, packageInfo);
        mPackagesHolder.put(packageInfo.packageName, pluginPackage);
        return pluginPackage;
    }

    private DexClassLoader createDexClassLoader(String dexPath) {
        File dexOutputDir = mContext.getDir("dex", Context.MODE_PRIVATE);
        dexOutputPath = dexOutputDir.getAbsolutePath();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            Log.d(TAG, "context classloader");
            classLoader = mContext.getClassLoader();
        }
        return new DexClassLoader(dexPath, dexOutputPath, mNativeLibDir, classLoader);
    }

    private AssetManager createAssetManager(String dexPath) {
        try {
            AssetManager assetManager = AssetManager.class.newInstance();
            Method addAssetPath = assetManager.getClass().getMethod("addAssetPath", String.class);
            addAssetPath.invoke(assetManager, dexPath);
            return assetManager;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    public DLPluginPackage getPackage(String packageName) {
        return mPackagesHolder.get(packageName);
    }

    private Resources createResources(AssetManager assetManager) {
        Resources superRes = mContext.getResources();
        Resources resources = new Resources(assetManager, superRes.getDisplayMetrics(), superRes.getConfiguration());
        return resources;
    }

    /**
     * copy .so file to pluginlib dir.
     */
    private void copySoLib(String dexPath) {
        // TODO: copy so lib async will lead to bugs maybe, waiting for
        // resolved later.

        // TODO : use wait and signal is ok ? that means when copying the
        // .so files, the main thread will enter waiting status, when the
        // copy is done, send a signal to the main thread.
        // new Thread(new CopySoRunnable(dexPath)).start();

        SoLibManager.getSoLoader().copyPluginSoLib(mContext, dexPath, mNativeLibDir);
    }

    /**
     * {@link #startPluginActivityForResult(Activity, String, DLIntent, int)}
     */
    public int startPluginActivity(Context hostContext, DLIntent dlIntent) {
        return startPluginActivityForResult(hostContext, dlIntent, -1);
    }

    /**
     * @return One of below: {@link #START_RESULT_SUCCESS}
     * {@link #START_RESULT_NO_PKG} {@link #START_RESULT_NO_CLASS}
     * {@link #START_RESULT_TYPE_ERROR}
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public int startPluginActivityForResult(Context hostContext, DLIntent dlIntent, int requestCode) {
        if (mFrom == DLConstants.FROM_INTERNAL) {
            dlIntent.setClassName(hostContext, dlIntent.getPluginClass());
            performStartActivityForResult(hostContext, dlIntent, requestCode);
            return DLPluginManager.START_RESULT_SUCCESS;
        }

        String packageName = dlIntent.getPluginPackage();
        Log.d(TAG, "packageName:" + packageName);
        if (TextUtils.isEmpty(packageName)) {
            throw new NullPointerException("disallow null packageName.");
        }

        DLPluginPackage pluginPackage = mPackagesHolder.get(packageName);
        if (pluginPackage == null) {
            return START_RESULT_NO_PKG;
        }

        final String className = getPluginActivityFullPath(dlIntent, pluginPackage);
        Class<?> clazz = loadPluginClass(pluginPackage.classLoader, className);
        if (clazz == null) {
            return START_RESULT_NO_CLASS;
        }

        // get the proxy activity class, the proxy activity will launch the
        // plugin activity.
        Class<? extends Activity> activityClass = getProxyActivityClass(clazz);
        if (activityClass == null) {
            return START_RESULT_TYPE_ERROR;
        }

        // put extra data
        dlIntent.putExtra(DLConstants.EXTRA_CLASS, className);
        dlIntent.putExtra(DLConstants.EXTRA_PACKAGE, packageName);
        dlIntent.setClass(mContext, activityClass);
        performStartActivityForResult(hostContext, dlIntent, requestCode);
        return START_RESULT_SUCCESS;
    }

    public int startPluginService(final Context context, final DLIntent dlIntent) {
        if (mFrom == DLConstants.FROM_INTERNAL) {
            dlIntent.setClassName(context, dlIntent.getPluginClass());
            context.startService(dlIntent);
            return DLPluginManager.START_RESULT_SUCCESS;
        }

        fetchProxyServiceClass(dlIntent, new OnFetchProxyServiceClass() {
            @Override
            public void onFetch(int result, Class<? extends Service> proxyServiceClass) {
                // TODO Auto-generated method stub
                if (result == START_RESULT_SUCCESS) {
                    dlIntent.setClass(context, proxyServiceClass);
                    // start代理Service
                    context.startService(dlIntent);
                }
                mResult = result;
            }
        });

        return mResult;
    }

    public int stopPluginService(final Context context, final DLIntent dlIntent) {
        if (mFrom == DLConstants.FROM_INTERNAL) {
            dlIntent.setClassName(context, dlIntent.getPluginClass());
            context.stopService(dlIntent);
            return DLPluginManager.START_RESULT_SUCCESS;
        }

        fetchProxyServiceClass(dlIntent, new OnFetchProxyServiceClass() {
            @Override
            public void onFetch(int result, Class<? extends Service> proxyServiceClass) {
                // TODO Auto-generated method stub
                if (result == START_RESULT_SUCCESS) {
                    dlIntent.setClass(context, proxyServiceClass);
                    // stop代理Service
                    context.stopService(dlIntent);
                }
                mResult = result;
            }
        });

        return mResult;
    }

    public int bindPluginService(final Context context, final DLIntent dlIntent, final ServiceConnection conn,
                                 final int flags) {
        if (mFrom == DLConstants.FROM_INTERNAL) {
            dlIntent.setClassName(context, dlIntent.getPluginClass());
            context.bindService(dlIntent, conn, flags);
            return DLPluginManager.START_RESULT_SUCCESS;
        }

        fetchProxyServiceClass(dlIntent, new OnFetchProxyServiceClass() {
            @Override
            public void onFetch(int result, Class<? extends Service> proxyServiceClass) {
                // TODO Auto-generated method stub
                if (result == START_RESULT_SUCCESS) {
                    dlIntent.setClass(context, proxyServiceClass);
                    // Bind代理Service
                    context.bindService(dlIntent, conn, flags);
                }
                mResult = result;
            }
        });

        return mResult;
    }

    public int unBindPluginService(final Context context, DLIntent dlIntent, final ServiceConnection conn) {
        if (mFrom == DLConstants.FROM_INTERNAL) {
            context.unbindService(conn);
            return DLPluginManager.START_RESULT_SUCCESS;
        }

        fetchProxyServiceClass(dlIntent, new OnFetchProxyServiceClass() {
            @Override
            public void onFetch(int result, Class<? extends Service> proxyServiceClass) {
                // TODO Auto-generated method stub
                if (result == START_RESULT_SUCCESS) {
                    // unBind代理Service
                    context.unbindService(conn);
                }
                mResult = result;
            }
        });
        return mResult;

    }

    public Class<?> getPluginClass(String packageName, String className) {
        if (TextUtils.isEmpty(packageName)) {
            throw new NullPointerException("disallow null packageName.");
        }
        DLPluginPackage pluginPackage = mPackagesHolder.get(packageName);
        if (pluginPackage == null) {
            return null;
        }

        Class<?> clazz = loadPluginClass(pluginPackage.classLoader, className);
        return clazz;
    }

    /**
     * 获取代理ServiceClass
     */
    private void fetchProxyServiceClass(DLIntent dlIntent, OnFetchProxyServiceClass fetchProxyServiceClass) {
        String packageName = dlIntent.getPluginPackage();
        if (TextUtils.isEmpty(packageName)) {
            throw new NullPointerException("disallow null packageName.");
        }
        DLPluginPackage pluginPackage = mPackagesHolder.get(packageName);
        if (pluginPackage == null) {
            fetchProxyServiceClass.onFetch(START_RESULT_NO_PKG, null);
            return;
        }

        // 获取要启动的Service的全名
        String className = dlIntent.getPluginClass();
        Class<?> clazz = loadPluginClass(pluginPackage.classLoader, className);
        if (clazz == null) {
            fetchProxyServiceClass.onFetch(START_RESULT_NO_CLASS, null);
            return;
        }

        Class<? extends Service> proxyServiceClass = getProxyServiceClass(clazz);
        if (proxyServiceClass == null) {
            fetchProxyServiceClass.onFetch(START_RESULT_TYPE_ERROR, null);
            return;
        }

        // put extra data
        dlIntent.putExtra(DLConstants.EXTRA_CLASS, className);
        dlIntent.putExtra(DLConstants.EXTRA_PACKAGE, packageName);
        fetchProxyServiceClass.onFetch(START_RESULT_SUCCESS, proxyServiceClass);
    }

    // zhangjie1980 重命名 loadPluginActivityClass -> loadPluginClass
    private Class<?> loadPluginClass(ClassLoader classLoader, String className) {
        Class<?> clazz = null;
        try {
            clazz = Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        return clazz;
    }

    private String getPluginActivityFullPath(DLIntent dlIntent, DLPluginPackage pluginPackage) {
        String className = dlIntent.getPluginClass();
        className = (className == null ? pluginPackage.defaultActivity : className);
        if (className.startsWith(".")) {
            className = dlIntent.getPluginPackage() + className;
        }
        return className;
    }

    /**
     * get the proxy activity class, the proxy activity will delegate the plugin
     * activity
     *
     * @param clazz target activity's class
     */
    private Class<? extends Activity> getProxyActivityClass(Class<?> clazz) {
        Class<? extends Activity> activityClass = null;
        if (BasePluginActivity.class.isAssignableFrom(clazz)) {
            activityClass = DLProxyActivity.class;
        } else if (BasePluginFragmentActivity.class.isAssignableFrom(clazz)) {
            activityClass = DLProxyFragmentActivity.class;
        }

        return activityClass;
    }

    private Class<? extends Service> getProxyServiceClass(Class<?> clazz) {
        Class<? extends Service> proxyServiceClass = null;
        if (BasePluginService.class.isAssignableFrom(clazz)) {
            proxyServiceClass = DLProxyService.class;
        }
        // 后续可能还有IntentService，待补充

        return proxyServiceClass;
    }

    public void performStartActivityForResult(Context context, DLIntent dlIntent, int requestCode) {
        Log.d(TAG, "launch " + dlIntent.getPluginClass());
        if (context instanceof Activity) {
            ((Activity) context).startActivityForResult(dlIntent, requestCode);
        } else {
            context.startActivity(dlIntent);
        }
    }

    private interface OnFetchProxyServiceClass {
        public void onFetch(int result, Class<? extends Service> proxyServiceClass);
    }

}
