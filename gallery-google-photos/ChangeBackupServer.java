/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.gallery;

import android.util.Log;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 相册云备份供应商切换（诊断增强版）。
 *
 * <p>相对官方原版的唯一区别：增加了面向真机的诊断日志，用于在 HyperOS 4 上
 * 定位 com.miui.gallery 到底缺失了哪些 Google Photos 相关类/方法。</p>
 *
 * <p>官方行为保持不变：{@code gallery_backup_server} = 1(小米云)/2(Google)/3(Google+OneDrive)。</p>
 *
 * <p>用法：直接替换本仓库
 * library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/gallery/ChangeBackupServer.java
 * 后重新编译即可。诊断日志用 adb 查看：adb logcat -s HyperCeilerGallery</p>
 */
public class ChangeBackupServer extends BaseHook {
    /** 独立日志 tag，方便 logcat 过滤，且不依赖 HyperCeiler 自身的日志级别开关。 */
    private static final String TAG = "HyperCeilerGallery";
    private static final ThreadLocal<Boolean> sOverrideIsInternational = new ThreadLocal<>();

    /**
     * 官方原版依赖的关键类。init() 时会逐个探测是否存在并打印，据此判断国行
     * HyperOS 4 的 Gallery APK 里到底缺了哪一环。
     */
    private static final String[] WATCH_CLASSES = {
        "com.miui.gallery.util.BaseBuildUtil",
        "com.miui.gallery.ui.GallerySettingsFragment",
        "com.miui.gallery.util.PhotoModelTypeUtil",
        "com.miui.gallery.transfer.GoogleSyncHelper",
        "com.miui.gallery.util.BuildUtil",
    };

    @Override
    public void init() {
        int backupServer = PrefsBridge.getStringAsInt("gallery_backup_server", 0);
        boolean isXiaomi = backupServer == 1;
        boolean isGoogle = backupServer == 2;
        boolean isOneDrive = backupServer == 3;

        logClassAvailability(backupServer);

        if (isOneDrive || isXiaomi) {
            findAndChainMethod("com.miui.gallery.util.BaseBuildUtil", "isInternational",
                chain -> {
                    Boolean override = sOverrideIsInternational.get();
                    if (override != null) {
                        return override;
                    }
                    return chain.proceed();
                }
            );
        }

        if (isOneDrive) {
            findAndChainMethod("com.miui.gallery.ui.GallerySettingsFragment",
                "initGlobalBackupPreference",
                chain -> {
                    sOverrideIsInternational.set(true);
                    try {
                        return chain.proceed();
                    } finally {
                        sOverrideIsInternational.remove();
                    }
                }
            );
            findAndHookMethod("com.miui.gallery.util.PhotoModelTypeUtil", "isSupportOneDrive", new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    param.setResult(true);
                }
            });
        } else {
            if (isXiaomi) {
                findAndChainMethod("com.miui.gallery.ui.GallerySettingsFragment",
                    "initGlobalBackupPreference",
                    chain -> {
                        sOverrideIsInternational.set(false);
                        try {
                            return chain.proceed();
                        } finally {
                            sOverrideIsInternational.remove();
                        }
                    }
                );
            }
            hookGoogleBackup(isGoogle);
        }
    }

    /**
     * Google 分支。官方原版是"先 hook GoogleSyncHelper，找不到再 fallback 到
     * BuildUtil.isGlobal"；这里显式探测并打印，把失败原因暴露出来。
     */
    private void hookGoogleBackup(boolean isGoogle) {
        boolean hooked = false;

        if (findClassIfExists("com.miui.gallery.transfer.GoogleSyncHelper") != null) {
            try {
                findAndHookMethod("com.miui.gallery.transfer.GoogleSyncHelper", "isCloudServiceOffLine", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        param.setResult(isGoogle);
                    }
                });
                hooked = true;
                Log.i(TAG, "[Google] hooked GoogleSyncHelper.isCloudServiceOffLine -> " + isGoogle);
            } catch (Throwable t) {
                Log.w(TAG, "[Google] GoogleSyncHelper 存在但 isCloudServiceOffLine hook 失败: " + t);
            }
        } else {
            Log.w(TAG, "[Google] GoogleSyncHelper 类不存在，尝试 fallback 到 BuildUtil.isGlobal");
        }

        if (!hooked && findClassIfExists("com.miui.gallery.util.BuildUtil") != null) {
            try {
                findAndHookMethod("com.miui.gallery.util.BuildUtil", "isGlobal", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        param.setResult(isGoogle);
                    }
                });
                hooked = true;
                Log.i(TAG, "[Google] hooked BuildUtil.isGlobal -> " + isGoogle);
            } catch (Throwable t) {
                Log.w(TAG, "[Google] BuildUtil.isGlobal hook 失败: " + t);
            }
        } else if (!hooked) {
            Log.w(TAG, "[Google] BuildUtil.isGlobal 也不存在，本 ROM 未命中任何已知 Google 类/方法");
        }

        if (!hooked) {
            Log.e(TAG, "[Google] 未命中任何已知 Google Photos 类/方法。"
                + "请提取本机 com.miui.gallery 的 APK/dex 并回传日志，以继续逆向。");
        }
    }

    private void logClassAvailability(int backupServer) {
        StringBuilder sb = new StringBuilder("backupServer=").append(backupServer);
        for (String cn : WATCH_CLASSES) {
            sb.append(" | ").append(cn).append('=').append(findClassIfExists(cn) != null ? "Y" : "N");
        }
        Log.i(TAG, sb.toString());
    }
}