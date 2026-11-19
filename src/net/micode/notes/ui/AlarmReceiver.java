/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    /**
     * AlarmManager 与提醒界面之间的轻量转发器。
     *
     * 清单文件把本 Receiver 放在独立进程中，系统闹钟到时即使主应用进程
     * 不存在也能收到广播。Receiver 不执行数据库或音频等耗时工作，
     * 只保留原 Intent 中的便签 URI，并把目标改为 AlarmAlertActivity。
     *
     * 这种设计让广播回调尽快返回，后续锁屏展示、摘要查询和铃声播放都由
     * 具有完整生命周期的 Activity 负责。
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        /*
         * 直接复用收到的 Intent，可保留设置闹钟时写入 data 的便签 URI。
         * AlarmAlertActivity 会从该 URI 解析便签 id，因此此处不能新建一个
         * 没有 data 的空 Intent。
         */
        intent.setClass(context, AlarmAlertActivity.class);
        /*
         * BroadcastReceiver 的 Context 不属于任何 Activity 任务栈，
         * 启动界面必须添加 NEW_TASK；否则系统会拒绝本次跳转。
         */
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 转交后立即返回，不在广播执行时间限制内进行提醒界面的实际工作。
        context.startActivity(intent);
    }
}
