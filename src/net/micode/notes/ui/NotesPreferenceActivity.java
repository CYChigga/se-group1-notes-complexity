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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;


/**
 * 设置页：Google Tasks 同步账户的绑定/解绑、手动同步与上次同步时间展示。
 * 本类同时充当同步配置的“单一数据源”——账户名与上次同步时间都存放在
 * SharedPreferences(notes_preferences) 中，其他类通过本类的静态方法读写，
 * 而不是各自打开偏好文件，避免键名散落。
 */
public class NotesPreferenceActivity extends PreferenceActivity {
    // 偏好文件名与四个键名。键值一旦改动，老用户的账户配置会“丢失”（读不到旧键），
    // 因此只能新增键，不能重命名已有键。
    public static final String PREFERENCE_NAME = "notes_preferences";

    public static final String PREFERENCE_SYNC_ACCOUNT_NAME = "pref_key_account_name";

    // 上次同步时间用 long 存时间戳，0 表示从未同步，界面据此隐藏状态文字。
    public static final String PREFERENCE_LAST_SYNC_TIME = "pref_last_sync_time";

    // 随机背景开关的键。本页不直接使用，但与账户配置同文件，改动时需一并回归。
    public static final String PREFERENCE_SET_BG_COLOR_KEY = "pref_key_bg_random_appear";

    // 同步账户分组在 preferences.xml 中的键，用于运行时向该分组动态插入账户项；
    // AUTHORITIES_FILTER_KEY 是跳转系统“添加账户”页面时过滤账户类型的参数名。
    private static final String PREFERENCE_SYNC_ACCOUNT_KEY = "pref_sync_account_key";

    private static final String AUTHORITIES_FILTER_KEY = "authorities";

    // mOriAccounts 记录打开账户选择框时的账户快照，
    // mHasAddedAccount 标记用户点过“添加账户”，二者配合用于 onResume 里自动选中新账户。
    private PreferenceCategory mAccountCategory;

    private GTaskReceiver mReceiver;

    private Account[] mOriAccounts;

    private boolean mHasAddedAccount;

    @Override
    // onCreate：注册同步进度广播接收器（同步在 Service 里跑，界面靠广播刷新），
    // 并在列表顶部插入自定义 header 视图（同步按钮与状态文字就放在 header 里）。
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        /* using the app icon for navigation */
        getActionBar().setDisplayHomeAsUpEnabled(true);

        // 偏好界面由 XML 声明，代码只补充动态部分（账户项、header），静态项不在此处创建。
        addPreferencesFromResource(R.xml.preferences);
        mAccountCategory = (PreferenceCategory) findPreference(PREFERENCE_SYNC_ACCOUNT_KEY);
        // 接收器只监听同步服务的自定义广播，不注册系统级 Action，减少无关唤醒。
        mReceiver = new GTaskReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(GTaskSyncService.GTASK_SERVICE_BROADCAST_NAME);
        registerReceiver(mReceiver, filter);

        // 快照初值为 null：表示还没有打开过账户选择框，onResume 里据此跳过自动绑定逻辑。
        mOriAccounts = null;
        View header = LayoutInflater.from(this).inflate(R.layout.settings_header, null);
        getListView().addHeaderView(header, null, true);
    }

    @Override
    /**
     * onResume 做两件事：
     * 1) 如果用户刚从系统账户页添加了新账户，自动把新增的那个设为同步账户——
     *    判据是“账户数变多且出现了快照里没有的账户名”，因此需要 mOriAccounts 快照；
     * 2) 刷新界面。账户信息可能被系统改动，所以每次回到前台都重读，不能只依赖 onCreate。
     */
    protected void onResume() {
        super.onResume();

        // need to set sync account automatically if user has added a new
        // account
        // 只有用户点过「添加账户」才走自动绑定，避免正常返回时被误判为新账户。
        if (mHasAddedAccount) {
            Account[] accounts = getGoogleAccounts();
            if (mOriAccounts != null && accounts.length > mOriAccounts.length) {
                for (Account accountNew : accounts) {
                    boolean found = false;
                    for (Account accountOld : mOriAccounts) {
                        if (TextUtils.equals(accountOld.name, accountNew.name)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        setSyncAccount(accountNew.name);
                        break;
                    }
                }
            }
        }

        // refreshUI 同时重建账户项与同步按钮，两者共享「是否已配置账户」这一前提。
        refreshUI();
    }

    @Override
    // 必须反注册广播接收器，否则 Activity 销毁后仍持有引用会造成泄漏。
    protected void onDestroy() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    /**
     * 重建“同步账户”偏好项。每次刷新都先清空分组再新建，
     * 这样账户名变化后不会残留旧条目。
     * 点击行为分三种：未配置账户→选账户；已配置→先警告再允许更换或解绑（换账户会重置本地同步标记）；
     * 正在同步→仅提示，不允许在同步过程中改账户。
     */
    private void loadAccountPreference() {
        // 先 removeAll 再添加：PreferenceCategory 没有「更新」接口，重建是唯一可靠的刷新方式。
        mAccountCategory.removeAll();

        // 账户项每次重建都新建 Preference 实例，不复用旧对象，避免监听器重复挂载。
        Preference accountPref = new Preference(this);
        final String defaultAccount = getSyncAccountName(this);
        accountPref.setTitle(getString(R.string.preferences_account_title));
        accountPref.setSummary(getString(R.string.preferences_account_summary));
        accountPref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                if (!GTaskSyncService.isSyncing()) {
                    if (TextUtils.isEmpty(defaultAccount)) {
                        // the first time to set account
                        showSelectAccountAlertDialog();
                    } else {
                        // if the account has already been set, we need to promp
                        // user about the risk
                        showChangeAccountConfirmAlertDialog();
                    }
                } else {
                    Toast.makeText(NotesPreferenceActivity.this,
                            R.string.preferences_toast_cannot_change_account, Toast.LENGTH_SHORT)
                            .show();
                }
                return true;
            }
        });

        mAccountCategory.addPreference(accountPref);
    }

    /**
     * 同步按钮与状态文字的渲染。按钮文案与点击行为随“是否正在同步”切换：
     * 同步中显示“取消”并调 cancelSync，空闲时显示“立即同步”并调 startSync。
     * 按钮可用性取决于是否已配置账户；未配置账户时禁用，避免点了没反应。
     * 状态文字同理：同步中显示服务端推送的进度串，空闲时显示上次同步时间（为 0 则隐藏）。
     */
    private void loadSyncButton() {
        // 同步按钮与状态文字都在 header 布局里，因此用 findViewById 而不是偏好项 API 获取。
        Button syncButton = (Button) findViewById(R.id.preference_sync_button);
        TextView lastSyncTimeView = (TextView) findViewById(R.id.prefenerece_sync_status_textview);

        // set button state
        // 同步中把按钮改成「取消」，点击即取消当前同步任务，而不是再发起一次。
        if (GTaskSyncService.isSyncing()) {
            syncButton.setText(getString(R.string.preferences_button_sync_cancel));
            syncButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    GTaskSyncService.cancelSync(NotesPreferenceActivity.this);
                }
            });
        } else {
            syncButton.setText(getString(R.string.preferences_button_sync_immediately));
            syncButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    GTaskSyncService.startSync(NotesPreferenceActivity.this);
                }
            });
        }
        // 未配置账户时禁用按钮：此时点同步没有意义，也避免触发需要账户的 Service。
        syncButton.setEnabled(!TextUtils.isEmpty(getSyncAccountName(this)));

        // set last sync time
        // 同步中优先显示进度串；空闲时才回落到「上次同步时间」，两者互斥显示。
        if (GTaskSyncService.isSyncing()) {
            lastSyncTimeView.setText(GTaskSyncService.getProgressString());
            lastSyncTimeView.setVisibility(View.VISIBLE);
        } else {
            long lastSyncTime = getLastSyncTime(this);
            if (lastSyncTime != 0) {
                lastSyncTimeView.setText(getString(R.string.preferences_last_sync_time,
                        DateFormat.format(getString(R.string.preferences_last_sync_time_format),
                                lastSyncTime)));
                lastSyncTimeView.setVisibility(View.VISIBLE);
            } else {
                lastSyncTimeView.setVisibility(View.GONE);
            }
        }
    }

    // 界面刷新统一入口：账户项与同步按钮一起重建，保证两处状态一致。
    private void refreshUI() {
        loadAccountPreference();
        loadSyncButton();
    }

    /**
     * 选择同步账户的对话框。除账户单选项外还提供“添加账户”入口：
     * 点击后跳到系统账户设置并只过滤 gmail 类型账户，同时把 mHasAddedAccount 置位，
     * 以便回到本页时 onResume 能识别出新增账户并自动绑定。
     * 边界：设备上一个 Google 账户都没有时，列表为空，只能走“添加账户”。
     */
    private void showSelectAccountAlertDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        View titleView = LayoutInflater.from(this).inflate(R.layout.account_dialog_title, null);
        TextView titleTextView = (TextView) titleView.findViewById(R.id.account_dialog_title);
        titleTextView.setText(getString(R.string.preferences_dialog_select_account_title));
        TextView subtitleTextView = (TextView) titleView.findViewById(R.id.account_dialog_subtitle);
        subtitleTextView.setText(getString(R.string.preferences_dialog_select_account_tips));

        dialogBuilder.setCustomTitle(titleView);
        dialogBuilder.setPositiveButton(null, null);

        // 每次打开都重新取账户列表：账户可能在系统设置中被删除，缓存会展示失效条目。
        Account[] accounts = getGoogleAccounts();
        String defAccount = getSyncAccountName(this);

        mOriAccounts = accounts;
        mHasAddedAccount = false;

        // 没有账户时不设置单选项，对话框只保留「添加账户」入口，避免出现空列表。
        if (accounts.length > 0) {
            CharSequence[] items = new CharSequence[accounts.length];
            final CharSequence[] itemMapping = items;
            int checkedItem = -1;
            int index = 0;
            for (Account account : accounts) {
                if (TextUtils.equals(account.name, defAccount)) {
                    checkedItem = index;
                }
                items[index++] = account.name;
            }
            dialogBuilder.setSingleChoiceItems(items, checkedItem,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            setSyncAccount(itemMapping[which].toString());
                            dialog.dismiss();
                            refreshUI();
                        }
                    });
        }

        // 「添加账户」作为对话框的底部视图而非列表项，点击后跳系统账户页并关闭当前框。
        View addAccountView = LayoutInflater.from(this).inflate(R.layout.add_account_text, null);
        dialogBuilder.setView(addAccountView);

        final AlertDialog dialog = dialogBuilder.show();
        addAccountView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mHasAddedAccount = true;
                Intent intent = new Intent("android.settings.ADD_ACCOUNT_SETTINGS");
                intent.putExtra(AUTHORITIES_FILTER_KEY, new String[] {
                    "gmail-ls"
                });
                startActivityForResult(intent, -1);
                dialog.dismiss();
            }
        });
    }

    // 已配置账户后再点账户项：先给出风险提示（换账户会清空本地同步标记），
    // 三个选项分别是更换、解绑、取消；解绑后要立刻刷新界面。
    private void showChangeAccountConfirmAlertDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        View titleView = LayoutInflater.from(this).inflate(R.layout.account_dialog_title, null);
        TextView titleTextView = (TextView) titleView.findViewById(R.id.account_dialog_title);
        titleTextView.setText(getString(R.string.preferences_dialog_change_account_title,
                getSyncAccountName(this)));
        TextView subtitleTextView = (TextView) titleView.findViewById(R.id.account_dialog_subtitle);
        subtitleTextView.setText(getString(R.string.preferences_dialog_change_account_warn_msg));
        dialogBuilder.setCustomTitle(titleView);

        // 更换/解绑/取消三项固定顺序，回调里用 which 下标判断，
        // 因此不能随便调整数组顺序，否则会把「取消」当成「解绑」。
        CharSequence[] menuItemArray = new CharSequence[] {
                getString(R.string.preferences_menu_change_account),
                getString(R.string.preferences_menu_remove_account),
                getString(R.string.preferences_menu_cancel)
        };
        dialogBuilder.setItems(menuItemArray, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    showSelectAccountAlertDialog();
                } else if (which == 1) {
                    removeSyncAccount();
                    refreshUI();
                }
            }
        });
        dialogBuilder.show();
    }

    // 只取 com.google 类型账户；账户读取属于系统服务调用，需在权限允许范围内使用。
    private Account[] getGoogleAccounts() {
        AccountManager accountManager = AccountManager.get(this);
        return accountManager.getAccountsByType("com.google");
    }

    /**
     * 写入同步账户并清理与账户绑定的本地同步状态：
     * 1) 账户名相同则直接返回，避免重复清理；
     * 2) 清空上次同步时间；
     * 3) 异步把全部便签的 GTASK_ID 置空、SYNC_ID 归零——
     *    这些字段是上一次绑定的远端标识，换账户后继续沿用会把本地数据错绑到别人的任务列表，
     *    因此必须在线程里批量清库（行数可能很多，不能放主线程）。
     */
    private void setSyncAccount(String account) {
        // 账户名未变则直接返回，防止用户重复点同一账户时触发无谓的清库操作。
        if (!getSyncAccountName(this).equals(account)) {
            SharedPreferences settings = getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = settings.edit();
            if (account != null) {
                editor.putString(PREFERENCE_SYNC_ACCOUNT_NAME, account);
            } else {
                editor.putString(PREFERENCE_SYNC_ACCOUNT_NAME, "");
            }
            editor.commit();

            // 换账户必须清掉上次同步时间，否则界面会显示上一个账户的同步记录。
            // clean up last sync time
            setLastSyncTime(this, 0);

            // clean up local gtask related info
            // 清库操作放在新线程：便签数量可能上千，主线程批量 update 会造成明显卡顿甚至 ANR。
            new Thread(new Runnable() {
                public void run() {
                    ContentValues values = new ContentValues();
                    values.put(NoteColumns.GTASK_ID, "");
                    values.put(NoteColumns.SYNC_ID, 0);
                    getContentResolver().update(Notes.CONTENT_NOTE_URI, values, null, null);
                }
            }).start();

            Toast.makeText(NotesPreferenceActivity.this,
                    getString(R.string.preferences_toast_success_set_accout, account),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // 解绑账户：移除账户名与上次同步时间两个键，并做与 setSyncAccount 相同的本地同步标记清理。
    // 注意这里不删除任何便签数据，只是断开与远端账户的关联。
    private void removeSyncAccount() {
        SharedPreferences settings = getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        if (settings.contains(PREFERENCE_SYNC_ACCOUNT_NAME)) {
            editor.remove(PREFERENCE_SYNC_ACCOUNT_NAME);
        }
        if (settings.contains(PREFERENCE_LAST_SYNC_TIME)) {
            editor.remove(PREFERENCE_LAST_SYNC_TIME);
        }
        editor.commit();

        // 解绑同样要清本地同步标记，保证重新绑定任意账户时都从干净状态开始。
        // clean up local gtask related info
        new Thread(new Runnable() {
            public void run() {
                ContentValues values = new ContentValues();
                values.put(NoteColumns.GTASK_ID, "");
                values.put(NoteColumns.SYNC_ID, 0);
                getContentResolver().update(Notes.CONTENT_NOTE_URI, values, null, null);
            }
        }).start();
    }

    // 静态读取入口。返回空串表示“未配置账户”，调用方据此判断是否处于同步模式。
    public static String getSyncAccountName(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getString(PREFERENCE_SYNC_ACCOUNT_NAME, "");
    }

    public static void setLastSyncTime(Context context, long time) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putLong(PREFERENCE_LAST_SYNC_TIME, time);
        editor.commit();
    }

    public static long getLastSyncTime(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getLong(PREFERENCE_LAST_SYNC_TIME, 0);
    }

    // 同步进度广播：每次收到都先刷新界面（按钮可能在“同步/取消”之间切换），
    // 若广播表示同步仍在进行，再把进度文案写到状态栏文字上。
    private class GTaskReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUI();
            // 广播里的 syncing 标志用于区分「进度更新」与「同步结束」，
            // 结束事件不带进度文案，此时只刷新按钮状态。
            if (intent.getBooleanExtra(GTaskSyncService.GTASK_SERVICE_BROADCAST_IS_SYNCING, false)) {
                TextView syncStatus = (TextView) findViewById(R.id.prefenerece_sync_status_textview);
                syncStatus.setText(intent
                        .getStringExtra(GTaskSyncService.GTASK_SERVICE_BROADCAST_PROGRESS_MSG));
            }

        }
    }

    // ActionBar 返回箭头：清栈回到列表页，避免设置页在返回栈里堆叠多份。
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                Intent intent = new Intent(this, NotesListActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
            default:
                return false;
        }
    }
}
