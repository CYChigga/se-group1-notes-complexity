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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.DataUtils;

import java.io.IOException;


public class AlarmAlertActivity extends Activity implements OnClickListener, OnDismissListener {
    /**
     * 提醒触发后的前台交互界面。
     *
     * AlarmReceiver 在独立进程中启动本 Activity，本类从 Intent.data 解析便签 id，
     * 再读取摘要、唤醒屏幕并循环播放系统闹钟铃声。用户可以关闭提醒，
     * 或在屏幕已点亮时直接进入对应便签。对话框消失是停止声音和结束界面的
     * 统一出口，避免不同按钮路径遗留正在播放的 MediaPlayer。
     */
    private long mNoteId;
    private String mSnippet;
    private static final int SNIPPET_PREW_MAX_LEN = 60;
    MediaPlayer mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /*
         * 提醒界面需要覆盖锁屏显示。若屏幕原本关闭，还要保持并点亮屏幕，
         * 使用户无需先手动解锁即可看到提醒内容。窗口标志必须在创建对话框前设置。
         */
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        final Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        if (!isScreenOn()) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        }

        Intent intent = getIntent();

        try {
            /*
             * AlarmManager 安排任务时把便签 URI 放入 Intent.data，第二段路径即便签 id。
             * 摘要过长时截断并追加提示字符，防止锁屏对话框被大段正文撑满。
             */
            mNoteId = Long.valueOf(intent.getData().getPathSegments().get(1));
            mSnippet = DataUtils.getSnippetById(this.getContentResolver(), mNoteId);
            mSnippet = mSnippet.length() > SNIPPET_PREW_MAX_LEN ? mSnippet.substring(0,
                    SNIPPET_PREW_MAX_LEN) + getResources().getString(R.string.notelist_string_info)
                    : mSnippet;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return;
        }

        mPlayer = new MediaPlayer();
        if (DataUtils.visibleInNoteDatabase(getContentResolver(), mNoteId, Notes.TYPE_NOTE)) {
            // 触发前再次验证便签可见，避免已经删除的记录仍弹出过期提醒。
            showActionDialog();
            playAlarmSound();
        } else {
            finish();
        }
    }

    private boolean isScreenOn() {
        // 屏幕状态决定是否提供“进入便签”按钮以及是否需要主动点亮屏幕。
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm.isScreenOn();
    }

    private void playAlarmSound() {
        /*
         * 使用用户配置的系统默认闹钟铃声，并循环播放直到对话框关闭。
         * MODE_RINGER_STREAMS_AFFECTED 是系统受静音模式影响的流集合，
         * 这里据此选择播放所用音频流，然后按 MediaPlayer 状态顺序初始化。
         */
        Uri url = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);

        int silentModeStreams = Settings.System.getInt(getContentResolver(),
                Settings.System.MODE_RINGER_STREAMS_AFFECTED, 0);

        if ((silentModeStreams & (1 << AudioManager.STREAM_ALARM)) != 0) {
            mPlayer.setAudioStreamType(silentModeStreams);
        } else {
            mPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
        }
        try {
            // setDataSource、prepare、setLooping、start 的顺序不可交换，否则状态机会报错。
            mPlayer.setDataSource(this, url);
            mPlayer.prepare();
            mPlayer.setLooping(true);
            mPlayer.start();
        } catch (IllegalArgumentException e) {
            // 铃声 URI 或参数非法时记录异常，提醒对话框仍可正常显示和关闭。
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SecurityException e) {
            // 系统拒绝读取铃声资源时不让异常终止提醒界面。
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalStateException e) {
            // MediaPlayer 状态异常说明初始化顺序或底层资源不可用，需要保留诊断信息。
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // 铃声数据无法读取时只降级为无声提醒，用户仍能处理便签。
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void showActionDialog() {
        /*
         * 正按钮始终用于确认并关闭提醒；屏幕原本开启时才提供进入便签的负按钮。
         * 无论点击按钮、返回键还是系统关闭对话框，OnDismissListener 都会负责清理声音。
         */
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle(R.string.app_name);
        dialog.setMessage(mSnippet);
        dialog.setPositiveButton(R.string.notealert_ok, this);
        if (isScreenOn()) {
            dialog.setNegativeButton(R.string.notealert_enter, this);
        }
        dialog.show().setOnDismissListener(this);
    }

    public void onClick(DialogInterface dialog, int which) {
        // 只有“进入便签”需要导航；确认按钮保持默认分支，随后由 dismiss 统一收尾。
        switch (which) {
            case DialogInterface.BUTTON_NEGATIVE:
                // 沿用提醒携带的便签 id，以 ACTION_VIEW 打开既有记录而不是新建便签。
                Intent intent = new Intent(this, NoteEditActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.putExtra(Intent.EXTRA_UID, mNoteId);
                startActivity(intent);
                break;
            default:
                break;
        }
    }

    public void onDismiss(DialogInterface dialog) {
        // 对话框生命周期结束即停止铃声并关闭透明 Activity，避免留在任务栈中。
        stopAlarmSound();
        finish();
    }

    private void stopAlarmSound() {
        /*
         * stop 后必须 release 才能归还音频资源，并把字段置空防止重复释放。
         * 该判空也允许播放初始化失败后的清理路径安全执行。
         */
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
    }
}
