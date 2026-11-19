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
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.model.WorkingNote.NoteSettingChangedListener;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.tool.ResourceParser.TextAppearanceResources;
import net.micode.notes.ui.DateTimePickerDialog.OnDateTimeSetListener;
import net.micode.notes.ui.NoteEditText.OnTextViewChangeListener;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class NoteEditActivity extends Activity implements OnClickListener,
        NoteSettingChangedListener, OnTextViewChangeListener {
    /**
     * 编辑界面的核心协调者。
     *
     * Activity 本身不直接操作数据库，而是把界面状态写入 WorkingNote，
     * 再由 WorkingNote 和 Note 将差异提交给 ContentProvider。这样可以把
     * Android 生命周期、界面交互与持久化细节分开，避免每次按键都访问数据库。
     *
     * 本类同时实现设置变更和清单文本回调，因此背景色、提醒、Widget、
     * 清单行增删等事件最终都会回到这里统一更新界面或触发保存。
     */
    private class HeadViewHolder {
        public TextView tvModified;

        public ImageView ivAlertIcon;

        public TextView tvAlertDate;

        public ImageView ibSetBgColor;
    }

    private static final Map<Integer, Integer> sBgSelectorBtnsMap = new HashMap<Integer, Integer>();
    static {
        // 将界面按钮 id 映射为业务使用的颜色编号，点击处理时无需编写重复分支。
        sBgSelectorBtnsMap.put(R.id.iv_bg_yellow, ResourceParser.YELLOW);
        sBgSelectorBtnsMap.put(R.id.iv_bg_red, ResourceParser.RED);
        sBgSelectorBtnsMap.put(R.id.iv_bg_blue, ResourceParser.BLUE);
        sBgSelectorBtnsMap.put(R.id.iv_bg_green, ResourceParser.GREEN);
        sBgSelectorBtnsMap.put(R.id.iv_bg_white, ResourceParser.WHITE);
    }

    private static final Map<Integer, Integer> sBgSelectorSelectionMap = new HashMap<Integer, Integer>();
    static {
        // 反向记录每种颜色对应的选中标记，用于初始化和切换时保持唯一选中状态。
        sBgSelectorSelectionMap.put(ResourceParser.YELLOW, R.id.iv_bg_yellow_select);
        sBgSelectorSelectionMap.put(ResourceParser.RED, R.id.iv_bg_red_select);
        sBgSelectorSelectionMap.put(ResourceParser.BLUE, R.id.iv_bg_blue_select);
        sBgSelectorSelectionMap.put(ResourceParser.GREEN, R.id.iv_bg_green_select);
        sBgSelectorSelectionMap.put(ResourceParser.WHITE, R.id.iv_bg_white_select);
    }

    private static final Map<Integer, Integer> sFontSizeBtnsMap = new HashMap<Integer, Integer>();
    static {
        // 字号选择控件只保存稳定的字号编号，不把具体 TextAppearance 分散到点击逻辑中。
        sFontSizeBtnsMap.put(R.id.ll_font_large, ResourceParser.TEXT_LARGE);
        sFontSizeBtnsMap.put(R.id.ll_font_small, ResourceParser.TEXT_SMALL);
        sFontSizeBtnsMap.put(R.id.ll_font_normal, ResourceParser.TEXT_MEDIUM);
        sFontSizeBtnsMap.put(R.id.ll_font_super, ResourceParser.TEXT_SUPER);
    }

    private static final Map<Integer, Integer> sFontSelectorSelectionMap = new HashMap<Integer, Integer>();
    static {
        // 字号编号与选中图标一一对应，显示选择面板时可直接恢复当前状态。
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_LARGE, R.id.iv_large_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_SMALL, R.id.iv_small_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_MEDIUM, R.id.iv_medium_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_SUPER, R.id.iv_super_select);
    }

    private static final String TAG = "NoteEditActivity";

    private HeadViewHolder mNoteHeaderHolder;

    private View mHeadViewPanel;

    private View mNoteBgColorSelector;

    private View mFontSizeSelector;

    private EditText mNoteEditor;

    private View mNoteEditorPanel;

    private WorkingNote mWorkingNote;

    private SharedPreferences mSharedPrefs;
    private int mFontSizeId;

    private static final String PREFERENCE_FONT_SIZE = "pref_font_size";

    private static final int SHORTCUT_ICON_TITLE_MAX_LEN = 10;

    public static final String TAG_CHECKED = String.valueOf('\u221A');
    public static final String TAG_UNCHECKED = String.valueOf('\u25A1');

    private LinearLayout mEditTextList;

    private String mUserQuery;
    private Pattern mPattern;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /*
         * 首次创建时必须先根据 Intent 建立 WorkingNote，再绑定界面资源。
         * 如果是系统重建，WorkingNote 会在 onRestoreInstanceState 中按便签 id 恢复，
         * 因而这里不能重复按原始 Intent 创建一条空便签。
         */
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.note_edit);

        if (savedInstanceState == null && !initActivityState(getIntent())) {
            finish();
            return;
        }
        initResources();
    }

    /**
     * Current activity may be killed when the memory is low. Once it is killed, for another time
     * user load this activity, we should restore the former state
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey(Intent.EXTRA_UID)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra(Intent.EXTRA_UID, savedInstanceState.getLong(Intent.EXTRA_UID));
            if (!initActivityState(intent)) {
                finish();
                return;
            }
            Log.d(TAG, "Restoring from killed activity");
        }
    }

    private boolean initActivityState(Intent intent) {
        /**
         * If the user specified the {@link Intent#ACTION_VIEW} but not provided with id,
         * then jump to the NotesListActivity
         */
        mWorkingNote = null;
        if (TextUtils.equals(Intent.ACTION_VIEW, intent.getAction())) {
            /*
             * ACTION_VIEW 表示打开已有便签。搜索结果会把真实 id 放在
             * SearchManager.EXTRA_DATA_KEY 中，同时传入用户查询词用于高亮。
             * 加载前先验证记录仍然可见，处理搜索结果过期或便签已删除的情况。
             */
            long noteId = intent.getLongExtra(Intent.EXTRA_UID, 0);
            mUserQuery = "";

            /**
             * Starting from the searched result
             */
            if (intent.hasExtra(SearchManager.EXTRA_DATA_KEY)) {
                noteId = Long.parseLong(intent.getStringExtra(SearchManager.EXTRA_DATA_KEY));
                mUserQuery = intent.getStringExtra(SearchManager.USER_QUERY);
            }

            if (!DataUtils.visibleInNoteDatabase(getContentResolver(), noteId, Notes.TYPE_NOTE)) {
                // 目标已不存在时退回列表，避免后续以无效 id 构造 WorkingNote。
                Intent jump = new Intent(this, NotesListActivity.class);
                startActivity(jump);
                showToast(R.string.error_note_not_exist);
                finish();
                return false;
            } else {
                mWorkingNote = WorkingNote.load(this, noteId);
                if (mWorkingNote == null) {
                    Log.e(TAG, "load note failed with note id" + noteId);
                    finish();
                    return false;
                }
            }
            getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } else if(TextUtils.equals(Intent.ACTION_INSERT_OR_EDIT, intent.getAction())) {
            // New note
            /*
             * 新建入口可能来自普通列表、桌面 Widget 或通话记录。
             * 这些来源共享同一编辑界面，但需要把文件夹、Widget 和背景色等上下文
             * 预先写入 WorkingNote，第一次保存时才能建立完整的数据库记录。
             */
            long folderId = intent.getLongExtra(Notes.INTENT_EXTRA_FOLDER_ID, 0);
            int widgetId = intent.getIntExtra(Notes.INTENT_EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            int widgetType = intent.getIntExtra(Notes.INTENT_EXTRA_WIDGET_TYPE,
                    Notes.TYPE_WIDGET_INVALIDE);
            int bgResId = intent.getIntExtra(Notes.INTENT_EXTRA_BACKGROUND_ID,
                    ResourceParser.getDefaultBgId(this));

            // Parse call-record note
            String phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            long callDate = intent.getLongExtra(Notes.INTENT_EXTRA_CALL_DATE, 0);
            if (callDate != 0 && phoneNumber != null) {
                /*
                 * 同一号码和通话时间只应对应一条通话便签。
                 * 若数据库中已有记录则直接加载，否则先创建空便签再转换类型，
                 * 防止重复接收通话事件时产生两条内容相同的记录。
                 */
                if (TextUtils.isEmpty(phoneNumber)) {
                    Log.w(TAG, "The call record number is null");
                }
                long noteId = 0;
                if ((noteId = DataUtils.getNoteIdByPhoneNumberAndCallDate(getContentResolver(),
                        phoneNumber, callDate)) > 0) {
                    mWorkingNote = WorkingNote.load(this, noteId);
                    if (mWorkingNote == null) {
                        Log.e(TAG, "load call note failed with note id" + noteId);
                        finish();
                        return false;
                    }
                } else {
                    mWorkingNote = WorkingNote.createEmptyNote(this, folderId, widgetId,
                            widgetType, bgResId);
                    mWorkingNote.convertToCallNote(phoneNumber, callDate);
                }
            } else {
                // 普通新建场景只创建内存对象，用户输入有效内容后才真正插入数据库。
                mWorkingNote = WorkingNote.createEmptyNote(this, folderId, widgetId, widgetType,
                        bgResId);
            }

            getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        } else {
            // 未声明支持的 action 无法判断打开还是新建，直接结束比误写数据库更安全。
            Log.e(TAG, "Intent not specified action, should not support");
            finish();
            return false;
        }
        mWorkingNote.setOnSettingStatusChangedListener(this);
        // 注册回调后，模型中的颜色、清单、提醒和 Widget 变化才能同步回当前界面。
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        initNoteScreen();
    }

    private void initNoteScreen() {
        /*
         * 每次回到前台都从 WorkingNote 重建可见状态。
         * 普通文本使用单个 EditText；清单文本则拆成若干带复选框的编辑行。
         * 两种模式最终仍共享同一个字符串内容，切换时必须完成双向转换。
         */
        mNoteEditor.setTextAppearance(this, TextAppearanceResources
                .getTexAppearanceResource(mFontSizeId));
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            switchToListMode(mWorkingNote.getContent());
        } else {
            mNoteEditor.setText(getHighlightQueryResult(mWorkingNote.getContent(), mUserQuery));
            mNoteEditor.setSelection(mNoteEditor.getText().length());
        }
        for (Integer id : sBgSelectorSelectionMap.keySet()) {
            // 先清除所有选择标记，避免 Activity 恢复后出现多个颜色同时选中的残留状态。
            findViewById(sBgSelectorSelectionMap.get(id)).setVisibility(View.GONE);
        }
        mHeadViewPanel.setBackgroundResource(mWorkingNote.getTitleBgResId());
        mNoteEditorPanel.setBackgroundResource(mWorkingNote.getBgColorResId());

        mNoteHeaderHolder.tvModified.setText(DateUtils.formatDateTime(this,
                mWorkingNote.getModifiedDate(), DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_SHOW_YEAR));

        /**
         * TODO: Add the menu for setting alert. Currently disable it because the DateTimePicker
         * is not ready
         */
        showAlertHeader();
    }

    private void showAlertHeader() {
        /*
         * 提醒时间保存在便签模型中。这里只负责展示，不重新安排 AlarmManager。
         * 已经过期的提醒仍显示“已过期”，便于用户识别旧设置并主动删除或重设。
         */
        if (mWorkingNote.hasClockAlert()) {
            long time = System.currentTimeMillis();
            if (time > mWorkingNote.getAlertDate()) {
                mNoteHeaderHolder.tvAlertDate.setText(R.string.note_alert_expired);
            } else {
                mNoteHeaderHolder.tvAlertDate.setText(DateUtils.getRelativeTimeSpanString(
                        mWorkingNote.getAlertDate(), time, DateUtils.MINUTE_IN_MILLIS));
            }
            mNoteHeaderHolder.tvAlertDate.setVisibility(View.VISIBLE);
            mNoteHeaderHolder.ivAlertIcon.setVisibility(View.VISIBLE);
        } else {
            mNoteHeaderHolder.tvAlertDate.setVisibility(View.GONE);
            mNoteHeaderHolder.ivAlertIcon.setVisibility(View.GONE);
        };
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        initActivityState(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        /**
         * For new note without note id, we should firstly save it to
         * generate a id. If the editing note is not worth saving, there
         * is no id which is equivalent to create new note
         */
        if (!mWorkingNote.existInDatabase()) {
            // 新便签只有成功保存后才有稳定 id，系统重建时才能定位同一条记录。
            saveNote();
        }
        outState.putLong(Intent.EXTRA_UID, mWorkingNote.getNoteId());
        Log.d(TAG, "Save working note id: " + mWorkingNote.getNoteId() + " onSaveInstanceState");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        /*
         * 颜色和字号选择器是临时浮层。触摸落在浮层外时先关闭浮层并消费事件，
         * 防止同一次触摸继续传给编辑器，造成光标移动或误触其他按钮。
         */
        if (mNoteBgColorSelector.getVisibility() == View.VISIBLE
                && !inRangeOfView(mNoteBgColorSelector, ev)) {
            mNoteBgColorSelector.setVisibility(View.GONE);
            return true;
        }

        if (mFontSizeSelector.getVisibility() == View.VISIBLE
                && !inRangeOfView(mFontSizeSelector, ev)) {
            mFontSizeSelector.setVisibility(View.GONE);
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean inRangeOfView(View view, MotionEvent ev) {
        // 将控件的屏幕坐标与触摸坐标比较，右边界和下边界也必须包含控件尺寸。
        int []location = new int[2];
        view.getLocationOnScreen(location);
        int x = location[0];
        int y = location[1];
        if (ev.getX() < x
                || ev.getX() > (x + view.getWidth())
                || ev.getY() < y
                || ev.getY() > (y + view.getHeight())) {
                    return false;
                }
        return true;
    }

    private void initResources() {
        /*
         * 该方法只进行控件查找、监听器注册和用户偏好恢复。
         * WorkingNote 的内容填充留给 initNoteScreen，避免资源绑定与业务状态混杂。
         */
        mHeadViewPanel = findViewById(R.id.note_title);
        mNoteHeaderHolder = new HeadViewHolder();
        mNoteHeaderHolder.tvModified = (TextView) findViewById(R.id.tv_modified_date);
        mNoteHeaderHolder.ivAlertIcon = (ImageView) findViewById(R.id.iv_alert_icon);
        mNoteHeaderHolder.tvAlertDate = (TextView) findViewById(R.id.tv_alert_date);
        mNoteHeaderHolder.ibSetBgColor = (ImageView) findViewById(R.id.btn_set_bg_color);
        mNoteHeaderHolder.ibSetBgColor.setOnClickListener(this);
        mNoteEditor = (EditText) findViewById(R.id.note_edit_view);
        mNoteEditorPanel = findViewById(R.id.sv_note_edit);
        mNoteBgColorSelector = findViewById(R.id.note_bg_color_selector);
        for (int id : sBgSelectorBtnsMap.keySet()) {
            ImageView iv = (ImageView) findViewById(id);
            iv.setOnClickListener(this);
        }

        mFontSizeSelector = findViewById(R.id.font_size_selector);
        for (int id : sFontSizeBtnsMap.keySet()) {
            View view = findViewById(id);
            view.setOnClickListener(this);
        };
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mFontSizeId = mSharedPrefs.getInt(PREFERENCE_FONT_SIZE, ResourceParser.BG_DEFAULT_FONT_SIZE);
        /**
         * HACKME: Fix bug of store the resource id in shared preference.
         * The id may larger than the length of resources, in this case,
         * return the {@link ResourceParser#BG_DEFAULT_FONT_SIZE}
         */
        if(mFontSizeId >= TextAppearanceResources.getResourcesSize()) {
            // 资源版本变化可能使旧偏好越界，此时回退到默认字号而不是直接索引数组。
            mFontSizeId = ResourceParser.BG_DEFAULT_FONT_SIZE;
        }
        mEditTextList = (LinearLayout) findViewById(R.id.note_edit_list);
    }

    @Override
    protected void onPause() {
        /*
         * 离开编辑界面时统一保存，可覆盖返回键、来电、切换应用等多种路径。
         * saveNote 内部会判断内容是否值得保存以及是否真的发生变化，
         * 因此生命周期回调可以安全地重复调用而不产生无意义写入。
         */
        super.onPause();
        if(saveNote()) {
            Log.d(TAG, "Note data was saved with length:" + mWorkingNote.getContent().length());
        }
        clearSettingState();
    }

    private void updateWidget() {
        /*
         * 只有与当前便签绑定的 Widget 类型才会收到刷新广播。
         * Widget id 作为附加参数传递，使 Provider 只更新对应实例而非全部桌面组件。
         */
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        if (mWorkingNote.getWidgetType() == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (mWorkingNote.getWidgetType() == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
            mWorkingNote.getWidgetId()
        });

        sendBroadcast(intent);
        setResult(RESULT_OK, intent);
    }

    public void onClick(View v) {
        /*
         * 所有背景色和字号按钮共用一个监听器，通过静态映射确定业务值。
         * 每次选择后立即隐藏面板，实际持久化仍由 WorkingNote 的差异保存机制完成。
         */
        int id = v.getId();
        if (id == R.id.btn_set_bg_color) {
            mNoteBgColorSelector.setVisibility(View.VISIBLE);
            findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                    -                    View.VISIBLE);
        } else if (sBgSelectorBtnsMap.containsKey(id)) {
            findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                    View.GONE);
            mWorkingNote.setBgColorId(sBgSelectorBtnsMap.get(id));
            mNoteBgColorSelector.setVisibility(View.GONE);
        } else if (sFontSizeBtnsMap.containsKey(id)) {
            findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.GONE);
            mFontSizeId = sFontSizeBtnsMap.get(id);
            mSharedPrefs.edit().putInt(PREFERENCE_FONT_SIZE, mFontSizeId).commit();
            findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.VISIBLE);
            if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
                getWorkingText();
                switchToListMode(mWorkingNote.getContent());
            } else {
                mNoteEditor.setTextAppearance(this,
                        TextAppearanceResources.getTexAppearanceResource(mFontSizeId));
            }
            mFontSizeSelector.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        // 返回键优先关闭设置浮层；只有没有临时面板时才真正保存并退出 Activity。
        if(clearSettingState()) {
            return;
        }

        saveNote();
        super.onBackPressed();
    }

    private boolean clearSettingState() {
        // 返回值表示本次是否只处理了浮层，用于阻止返回键同时结束编辑界面。
        if (mNoteBgColorSelector.getVisibility() == View.VISIBLE) {
            mNoteBgColorSelector.setVisibility(View.GONE);
            return true;
        } else if (mFontSizeSelector.getVisibility() == View.VISIBLE) {
            mFontSizeSelector.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    public void onBackgroundColorChanged() {
        // 模型颜色变化后同步选择标记、标题和正文背景，保证界面使用同一主题编号。
        findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                View.VISIBLE);
        mNoteEditorPanel.setBackgroundResource(mWorkingNote.getBgColorResId());
        mHeadViewPanel.setBackgroundResource(mWorkingNote.getTitleBgResId());
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        /*
         * 菜单依赖当前便签类型和状态，因此每次打开前都重新构建。
         * 通话记录使用受限菜单；提醒相关菜单则在“设置”和“删除”之间互斥显示。
         */
        if (isFinishing()) {
            return true;
        }
        clearSettingState();
        menu.clear();
        if (mWorkingNote.getFolderId() == Notes.ID_CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_note_edit, menu);
        } else {
            getMenuInflater().inflate(R.menu.note_edit, menu);
        }
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            menu.findItem(R.id.menu_list_mode).setTitle(R.string.menu_normal_mode);
        } else {
            menu.findItem(R.id.menu_list_mode).setTitle(R.string.menu_list_mode);
        }
        if (mWorkingNote.hasClockAlert()) {
            menu.findItem(R.id.menu_alert).setVisible(false);
        } else {
            menu.findItem(R.id.menu_delete_remind).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // 菜单动作只更新 WorkingNote 或启动外部行为，最终保存仍集中在 saveNote。
        switch (item.getItemId()) {
            case R.id.menu_new_note:
                createNewNote();
                break;
            case R.id.menu_delete:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_note));
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                deleteCurrentNote();
                                finish();
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
                break;
            case R.id.menu_font_size:
                mFontSizeSelector.setVisibility(View.VISIBLE);
                findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.VISIBLE);
                break;
            case R.id.menu_list_mode:
                mWorkingNote.setCheckListMode(mWorkingNote.getCheckListMode() == 0 ?
                        TextNote.MODE_CHECK_LIST : 0);
                break;
            case R.id.menu_share:
                getWorkingText();
                sendTo(this, mWorkingNote.getContent());
                break;
            case R.id.menu_send_to_desktop:
                sendToDesktop();
                break;
            case R.id.menu_alert:
                setReminder();
                break;
            case R.id.menu_delete_remind:
                mWorkingNote.setAlertDate(0, false);
                break;
            default:
                break;
        }
        return true;
    }

    private void setReminder() {
        /*
         * 选择器以当前时间作为初值。用户确认后只把毫秒时间写入模型；
         * WorkingNote 随即通过设置变更监听器回调 onClockAlertChanged，
         * 在该回调中保证便签已有 id 并安排系统闹钟。
         */
        DateTimePickerDialog d = new DateTimePickerDialog(this, System.currentTimeMillis());
        d.setOnDateTimeSetListener(new OnDateTimeSetListener() {
            public void OnDateTimeSet(AlertDialog dialog, long date) {
                mWorkingNote.setAlertDate(date	, true);
            }
        });
        d.show();
    }

    /**
     * Share note to apps that support {@link Intent#ACTION_SEND} action
     * and {@text/plain} type
     */
    private void sendTo(Context context, String info) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, info);
        intent.setType("text/plain");
        context.startActivity(intent);
    }

    private void createNewNote() {
        /*
         * 新建下一条便签前先保存当前编辑内容，再结束本 Activity 并启动新实例。
         * 这样新旧 WorkingNote 不会共用界面状态，同时沿用当前文件夹作为目标位置。
         */
        // Firstly, save current editing notes
        saveNote();

        // For safety, start a new NoteEditActivity
        finish();
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mWorkingNote.getFolderId());
        startActivity(intent);
    }

    private void deleteCurrentNote() {
        /*
         * 未启用同步时直接批量删除；启用同步时移动到回收站，
         * 让后续同步能够把删除意图传播到远端。最后标记 WorkingNote 已删除，
         * 防止 onPause 再次把刚删除的内容保存回来。
         */
        if (mWorkingNote.existInDatabase()) {
            HashSet<Long> ids = new HashSet<Long>();
            long id = mWorkingNote.getNoteId();
            if (id != Notes.ID_ROOT_FOLDER) {
                ids.add(id);
            } else {
                Log.d(TAG, "Wrong note id, should not happen");
            }
            if (!isSyncMode()) {
                if (!DataUtils.batchDeleteNotes(getContentResolver(), ids)) {
                    Log.e(TAG, "Delete Note error");
                }
            } else {
                if (!DataUtils.batchMoveToFolder(getContentResolver(), ids, Notes.ID_TRASH_FOLER)) {
                    Log.e(TAG, "Move notes to trash folder error, should not happens");
                }
            }
        }
        mWorkingNote.markDeleted(true);
    }

    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    public void onClockAlertChanged(long date, boolean set) {
        /**
         * User could set clock to an unsaved note, so before setting the
         * alert clock, we should save the note first
         */
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }
        if (mWorkingNote.getNoteId() > 0) {
            /*
             * 便签 URI 被放进 Intent.data，系统会把不同便签视为不同 PendingIntent。
             * 同一个便签再次设置时则能取得等价 PendingIntent，从而覆盖或取消旧闹钟。
             */
            Intent intent = new Intent(this, AlarmReceiver.class);
            intent.setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mWorkingNote.getNoteId()));
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            AlarmManager alarmManager = ((AlarmManager) getSystemService(ALARM_SERVICE));
            showAlertHeader();
            if(!set) {
                // 删除提醒时取消系统闹钟；提醒日期字段已由 WorkingNote 更新为传入值。
                alarmManager.cancel(pendingIntent);
            } else {
                // RTC_WAKEUP 使用绝对墙上时间，并允许设备休眠时唤醒接收器。
                alarmManager.set(AlarmManager.RTC_WAKEUP, date, pendingIntent);
            }
        } else {
            /**
             * There is the condition that user has input nothing (the note is
             * not worthy saving), we have no note id, remind the user that he
             * should input something
             */
            Log.e(TAG, "Clock alert setting error");
            showToast(R.string.error_note_empty_for_clock);
        }
    }

    public void onWidgetChanged() {
        updateWidget();
    }

    public void onEditTextDelete(int index, String text) {
        /*
         * 清单行在行首按退格时与上一行合并。
         * 删除当前 View 后必须重排后续行的索引，否则下一次回调会定位错误；
         * 合并后把光标放在原上一行末尾，符合连续编辑的预期。
         */
        int childCount = mEditTextList.getChildCount();
        if (childCount == 1) {
            return;
        }

        for (int i = index + 1; i < childCount; i++) {
            ((NoteEditText) mEditTextList.getChildAt(i).findViewById(R.id.et_edit_text))
                    .setIndex(i - 1);
        }

        mEditTextList.removeViewAt(index);
        NoteEditText edit = null;
        if(index == 0) {
            edit = (NoteEditText) mEditTextList.getChildAt(0).findViewById(
                    R.id.et_edit_text);
        } else {
            edit = (NoteEditText) mEditTextList.getChildAt(index - 1).findViewById(
                    R.id.et_edit_text);
        }
        int length = edit.length();
        edit.append(text);
        edit.requestFocus();
        edit.setSelection(length);
    }

    public void onEditTextEnter(int index, String text) {
        /*
         * 回车把光标后的文本拆到新行。新 View 插入指定位置后，
         * 后续行索引整体加一，并把焦点放到新行开头。
         */
        /**
         * Should not happen, check for debug
         */
        if(index > mEditTextList.getChildCount()) {
            Log.e(TAG, "Index out of mEditTextList boundrary, should not happen");
        }

        View view = getListItem(text, index);
        mEditTextList.addView(view, index);
        NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
        edit.requestFocus();
        edit.setSelection(0);
        for (int i = index + 1; i < mEditTextList.getChildCount(); i++) {
            ((NoteEditText) mEditTextList.getChildAt(i).findViewById(R.id.et_edit_text))
                    .setIndex(i);
        }
    }

    private void switchToListMode(String text) {
        /*
         * 存储格式以换行分隔条目，并用特殊字符标识勾选状态。
         * 重建列表时跳过空行，最后始终补一个空编辑项，便于用户继续追加内容。
         */
        mEditTextList.removeAllViews();
        String[] items = text.split("\n");
        int index = 0;
        for (String item : items) {
            if(!TextUtils.isEmpty(item)) {
                mEditTextList.addView(getListItem(item, index));
                index++;
            }
        }
        mEditTextList.addView(getListItem("", index));
        mEditTextList.getChildAt(index).findViewById(R.id.et_edit_text).requestFocus();

        mNoteEditor.setVisibility(View.GONE);
        mEditTextList.setVisibility(View.VISIBLE);
    }

    private Spannable getHighlightQueryResult(String fullText, String userQuery) {
        /*
         * 从搜索结果进入编辑页时，仅为匹配片段附加背景色 Span，
         * 不改变 WorkingNote 保存的原始字符串。空查询直接返回普通 Spannable。
         */
        SpannableString spannable = new SpannableString(fullText == null ? "" : fullText);
        if (!TextUtils.isEmpty(userQuery)) {
            mPattern = Pattern.compile(userQuery);
            Matcher m = mPattern.matcher(fullText);
            int start = 0;
            while (m.find(start)) {
                spannable.setSpan(
                        new BackgroundColorSpan(this.getResources().getColor(
                                R.color.user_query_highlight)), m.start(), m.end(),
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                start = m.end();
            }
        }
        return spannable;
    }

    private View getListItem(String item, int index) {
        /*
         * 每个清单项由 CheckBox 和 NoteEditText 组成。
         * 勾选状态既影响删除线，也会在保存时重新编码为前缀字符；
         * 读取旧内容时需要先移除前缀，避免标记出现在可编辑正文中。
         */
        View view = LayoutInflater.from(this).inflate(R.layout.note_edit_list_item, null);
        final NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
        edit.setTextAppearance(this, TextAppearanceResources.getTexAppearanceResource(mFontSizeId));
        CheckBox cb = ((CheckBox) view.findViewById(R.id.cb_edit_item));
        cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    edit.setPaintFlags(edit.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    edit.setPaintFlags(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
                }
            }
        });

        if (item.startsWith(TAG_CHECKED)) {
            cb.setChecked(true);
            edit.setPaintFlags(edit.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            item = item.substring(TAG_CHECKED.length(), item.length()).trim();
        } else if (item.startsWith(TAG_UNCHECKED)) {
            cb.setChecked(false);
            edit.setPaintFlags(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
            item = item.substring(TAG_UNCHECKED.length(), item.length()).trim();
        }

        edit.setOnTextViewChangeListener(this);
        edit.setIndex(index);
        edit.setText(getHighlightQueryResult(item, mUserQuery));
        return view;
    }

    public void onTextChange(int index, boolean hasText) {
        // 空的占位行不显示复选框，有内容后再显示，减少清单末尾的视觉干扰。
        if (index >= mEditTextList.getChildCount()) {
            Log.e(TAG, "Wrong index, should not happen");
            return;
        }
        if(hasText) {
            mEditTextList.getChildAt(index).findViewById(R.id.cb_edit_item).setVisibility(View.VISIBLE);
        } else {
            mEditTextList.getChildAt(index).findViewById(R.id.cb_edit_item).setVisibility(View.GONE);
        }
    }

    public void onCheckListModeChanged(int oldMode, int newMode) {
        /*
         * 切换模式前后必须先把当前控件内容收敛到 WorkingNote。
         * 从清单转普通文本时，只有不存在已勾选项才移除未勾选前缀，
         * 避免在有混合状态时丢失完成信息。
         */
        if (newMode == TextNote.MODE_CHECK_LIST) {
            switchToListMode(mNoteEditor.getText().toString());
        } else {
            if (!getWorkingText()) {
                mWorkingNote.setWorkingText(mWorkingNote.getContent().replace(TAG_UNCHECKED + " ",
                        ""));
            }
            mNoteEditor.setText(getHighlightQueryResult(mWorkingNote.getContent(), mUserQuery));
            mEditTextList.setVisibility(View.GONE);
            mNoteEditor.setVisibility(View.VISIBLE);
        }
    }

    private boolean getWorkingText() {
        /*
         * 这是界面状态进入模型的唯一汇合点。
         * 清单模式逐行编码勾选标记并忽略空项；普通模式直接读取 EditText。
         * 返回值表示是否存在已勾选项，供模式切换时决定能否安全去除标记。
         */
        boolean hasChecked = false;
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mEditTextList.getChildCount(); i++) {
                View view = mEditTextList.getChildAt(i);
                NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
                if (!TextUtils.isEmpty(edit.getText())) {
                    if (((CheckBox) view.findViewById(R.id.cb_edit_item)).isChecked()) {
                        sb.append(TAG_CHECKED).append(" ").append(edit.getText()).append("\n");
                        hasChecked = true;
                    } else {
                        sb.append(TAG_UNCHECKED).append(" ").append(edit.getText()).append("\n");
                    }
                }
            }
            mWorkingNote.setWorkingText(sb.toString());
        } else {
            mWorkingNote.setWorkingText(mNoteEditor.getText().toString());
        }
        return hasChecked;
    }

    private boolean saveNote() {
        /*
         * 先采集界面文本，再交给 WorkingNote 判断新建、更新或无需保存。
         * 保存成功后设置 RESULT_OK，列表页据此区分返回原位置和新增后刷新到顶部。
         */
        getWorkingText();
        boolean saved = mWorkingNote.saveNote();
        if (saved) {
            /**
             * There are two modes from List view to edit view, open one note,
             * create/edit a node. Opening node requires to the original
             * position in the list when back from edit view, while creating a
             * new node requires to the top of the list. This code
             * {@link #RESULT_OK} is used to identify the create/edit state
             */
            setResult(RESULT_OK);
        }
        return saved;
    }

    private void sendToDesktop() {
        /**
         * Before send message to home, we should make sure that current
         * editing note is exists in databases. So, for new note, firstly
         * save it
         */
        if (!mWorkingNote.existInDatabase()) {
            // 桌面快捷方式依赖稳定便签 id，因此新便签必须先尝试持久化。
            saveNote();
        }

        if (mWorkingNote.getNoteId() > 0) {
            Intent sender = new Intent();
            Intent shortcutIntent = new Intent(this, NoteEditActivity.class);
            shortcutIntent.setAction(Intent.ACTION_VIEW);
            shortcutIntent.putExtra(Intent.EXTRA_UID, mWorkingNote.getNoteId());
            sender.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            sender.putExtra(Intent.EXTRA_SHORTCUT_NAME,
                    makeShortcutIconTitle(mWorkingNote.getContent()));
            sender.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.drawable.icon_app));
            sender.putExtra("duplicate", true);
            sender.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
            showToast(R.string.info_note_enter_desktop);
            sendBroadcast(sender);
        } else {
            /**
             * There is the condition that user has input nothing (the note is
             * not worthy saving), we have no note id, remind the user that he
             * should input something
             */
            Log.e(TAG, "Send to desktop error");
            showToast(R.string.error_note_empty_for_send_to_desktop);
        }
    }

    private String makeShortcutIconTitle(String content) {
        // 快捷方式标题去除清单状态符号并限制长度，避免桌面标签被控制字符污染。
        content = content.replace(TAG_CHECKED, "");
        content = content.replace(TAG_UNCHECKED, "");
        return content.length() > SHORTCUT_ICON_TITLE_MAX_LEN ? content.substring(0,
                SHORTCUT_ICON_TITLE_MAX_LEN) : content;
    }

    private void showToast(int resId) {
        showToast(resId, Toast.LENGTH_SHORT);
    }

    private void showToast(int resId, int duration) {
        Toast.makeText(this, resId, duration).show();
    }
}
