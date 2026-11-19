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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;


public class AlarmInitReceiver extends BroadcastReceiver {
    /**
     * 设备启动后恢复尚未触发的便签提醒。
     *
     * AlarmManager 的普通任务在设备重启后会丢失，因此收到 BOOT_COMPLETED 时
     * 必须从数据库重新查询未来提醒，并为每条便签重新创建 PendingIntent。
     * 本接收器只恢复调度，不展示界面；真正触发后由 AlarmReceiver 转交处理。
     */

    private static final String [] PROJECTION = new String [] {
        /*
         * 恢复闹钟只需要便签 id 和提醒时间，保持最小投影可减少启动阶段的查询开销。
         * 两个列索引必须与投影顺序一致。
         */
        NoteColumns.ID,
        NoteColumns.ALERTED_DATE
    };

    private static final int COLUMN_ID                = 0;
    private static final int COLUMN_ALERTED_DATE      = 1;

    @Override
    public void onReceive(Context context, Intent intent) {
        /*
         * 只查询当前时间之后的普通便签，过期提醒和文件夹都不应重新安排。
         * 时间以字符串参数绑定，避免把动态毫秒值直接拼进 selection。
         */
        long currentDate = System.currentTimeMillis();
        Cursor c = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                PROJECTION,
                NoteColumns.ALERTED_DATE + ">? AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE,
                new String[] { String.valueOf(currentDate) },
                null);

        if (c != null) {
            // 空结果集的 moveToFirst 返回 false；非空结果用 do-while 覆盖第一行。
            if (c.moveToFirst()) {
                do {
                    /*
                     * 便签 URI 放入 Intent.data 后参与 PendingIntent 身份比较，
                     * 即使 requestCode 都为零，不同便签仍会得到互不覆盖的闹钟。
                     */
                    long alertDate = c.getLong(COLUMN_ALERTED_DATE);
                    Intent sender = new Intent(context, AlarmReceiver.class);
                    sender.setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, c.getLong(COLUMN_ID)));
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, sender, 0);
                    AlarmManager alermManager = (AlarmManager) context
                            .getSystemService(Context.ALARM_SERVICE);
                    // RTC_WAKEUP 在到达绝对时间时唤醒设备，行为与编辑页新设提醒一致。
                    alermManager.set(AlarmManager.RTC_WAKEUP, alertDate, pendingIntent);
                } while (c.moveToNext());
            }
            // Receiver 结束前显式关闭 Cursor，避免系统进程长期持有数据库资源。
            c.close();
        }
    }
}
