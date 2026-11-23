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
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnTouchListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.BackupUtils;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;

/**
 * 便签列表页（应用主页）。承担“展示 + 导航 + 批量操作”三类职责：
 * 展示：把 NotesProvider 的 Cursor 交给 NotesListAdapter，列表项由 NotesListItem 渲染；
 * 导航：在根目录/子文件夹/通话记录文件夹之间切换，并同步标题栏与“新建”按钮的可见性；
 * 批处理：长按便签进入多选（ActionMode），支持批量删除与批量移动。
 * 查询一律走 AsyncQueryHandler 在后台线程完成，结果回到 onQueryComplete 再交给适配器，
 * 因此本类里所有 Cursor 的生命周期都由适配器接管，本类不 close 游标。
 */
public class NotesListActivity extends Activity implements OnClickListener, OnItemLongClickListener {
    // 两个查询令牌：AsyncQueryHandler 用同一个回调分发两类查询，
    // 靠 token 区分“便签列表”与“目标文件夹列表”，不能复用同一个值。
    private static final int FOLDER_NOTE_LIST_QUERY_TOKEN = 0;

    // 与便签列表查询共用一个回调，因此两个常量值必须不同。
    private static final int FOLDER_LIST_QUERY_TOKEN      = 1;

    // 文件夹右键菜单项 id，仅在 onContextItemSelected 中使用。
    private static final int MENU_FOLDER_DELETE = 0;

    private static final int MENU_FOLDER_VIEW = 1;

    private static final int MENU_FOLDER_CHANGE_NAME = 2;

    // 首次启动写入“使用说明”便签的一次性标记：值为 true 后不再重复写入，
    // 因此卸载重装或清数据后说明便签会再次出现，这是预期行为。
    private static final String PREFERENCE_ADD_INTRODUCTION = "net.micode.notes.introduction";

    /**
     * 列表页的三种界面状态。返回键、选项菜单、列表点击的含义都依赖它分派：
     * NOTE_LIST         根目录，点击文件夹进入下一级，点击便签打开编辑页；
     * SUB_FOLDER        普通子文件夹，标题栏显示文件夹名，不允许再向下进入；
     * CALL_RECORD_FOLDER 通话记录文件夹，只读语义，隐藏“新建便签”按钮。
     */
    private enum ListEditState {
        NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER
    };

    // 当前界面状态，决定返回键、菜单与点击行为，初值在 initResources 中置为 NOTE_LIST。
    private ListEditState mState;

    // 查询处理器实例在整个 Activity 生命周期内复用，不随查询次数重建。
    private BackgroundQueryHandler mBackgroundQueryHandler;

    private NotesListAdapter mNotesListAdapter;

    private ListView mNotesListView;

    private Button mAddNewNote;

    private boolean mDispatch;

    private int mOriginY;

    private int mDispatchY;

    private TextView mTitleBar;

    // 当前所在文件夹 id。所有查询与新建操作都以它为父节点，切换文件夹时必须先改这里。
    private long mCurrentFolderId;

    private ContentResolver mContentResolver;

    private ModeCallback mModeCallBack;

    private static final String TAG = "NotesListActivity";

    public static final int NOTES_LISTVIEW_SCROLL_RATE = 30;

    // 最近一次长按/点击的条目数据。多选与文件夹上下文菜单都从这里取 id 与名称，
    // 属于「临时焦点」，切换界面状态后不再有效。
    private NoteItemData mFocusNoteDataItem;

    // 子文件夹内的查询条件：只按父文件夹 id 过滤，不加类型限制，
    // 因此文件夹内若混有系统条目也会被一并列出（基线行为，未做修正）。
    private static final String NORMAL_SELECTION = NoteColumns.PARENT_ID + "=?";

    // 根目录的查询条件，是理解本类数据可见性的关键：
    // 前半段取“非系统类型 且 父 id 为根”的所有条目，即普通便签与用户文件夹；
    // 后半段单独把通话记录文件夹捞出来，且要求 NOTES_COUNT>0——空文件夹不显示；
    // 两段用 OR 连接后整体加括号，避免与外部拼接条件产生优先级错误。
    private static final String ROOT_FOLDER_SELECTION = "(" + NoteColumns.TYPE + "<>"
            + Notes.TYPE_SYSTEM + " AND " + NoteColumns.PARENT_ID + "=?)" + " OR ("
            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER + " AND "
            + NoteColumns.NOTES_COUNT + ">0)";

    // 打开/新建便签的返回码：两者都表示“列表可能已变化”，用于触发重新查询。
    private final static int REQUEST_CODE_OPEN_NODE = 102;
    private final static int REQUEST_CODE_NEW_NODE  = 103;

    // onCreate 只做视图与状态初始化；真正的数据查询放在 onStart，
    // 这样从编辑页返回时也能自动刷新，无需额外回调。
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_list);
        initResources();

        /**
         * Insert an introduction when user firstly use this application
         */
        setAppInfoFromRawRes();
    }

    // 编辑页返回后强制让适配器换一次游标（changeCursor(null) 会触发 CursorAdapter 重新查询），
    // 比手动比对数据更省事；非本类的 requestCode 一律交回父类处理。
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 只看 RESULT_OK：用户按返回键取消编辑时列表无需刷新，省一次查询。
        if (resultCode == RESULT_OK
                && (requestCode == REQUEST_CODE_OPEN_NODE || requestCode == REQUEST_CODE_NEW_NODE)) {
            mNotesListAdapter.changeCursor(null);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * 首次启动时把 res/raw/introduction 的文本写成一条便签，作为使用说明。
     * 边界条件：只有保存成功才置位一次性标记，避免写库失败后用户永远看不到说明；
     * 读资源失败或 IO 异常只记日志并返回，不阻塞列表页启动。
     */
    private void setAppInfoFromRawRes() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (!sp.getBoolean(PREFERENCE_ADD_INTRODUCTION, false)) {
            // 逐块读取而不是一次读成字符串：说明文本长度不固定，分块可避免一次性分配过大缓冲。
            StringBuilder sb = new StringBuilder();
            InputStream in = null;
            try {
                 // 资源 id 固定，若 res/raw/introduction 被删除会抛异常，因此这里判空后再读。
                 in = getResources().openRawResource(R.raw.introduction);
                if (in != null) {
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader br = new BufferedReader(isr);
                    char [] buf = new char[1024];
                    int len = 0;
                    while ((len = br.read(buf)) > 0) {
                        sb.append(buf, 0, len);
                    }
                } else {
                    Log.e(TAG, "Read introduction file error");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            } finally {
                if(in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }

            // 说明便签固定落在根目录、不绑定小部件、使用红色背景，这些参数决定了它在列表中的位置与外观。
            WorkingNote note = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_WIDGET_INVALIDE,
                    ResourceParser.RED);
            note.setWorkingText(sb.toString());
            if (note.saveNote()) {
                // 保存成功才写标记；commit 是同步落盘，保证下次启动一定能读到该标记。
                sp.edit().putBoolean(PREFERENCE_ADD_INTRODUCTION, true).commit();
            } else {
                Log.e(TAG, "Save introduction note error");
                return;
            }
        }
    }

    // 每次回到前台都重新查询：既能覆盖从编辑页返回的场景，
    // 也能覆盖同步服务在后台改库后回到列表的场景。
    @Override
    protected void onStart() {
        super.onStart();
        startAsyncNotesListQuery();
    }

    /**
     * 初始化视图、适配器与交互监听。
     * 注意底部 footer 的用法：通过 addFooterView + setOnItemClickListener 的组合，
     * 让“列表底部提示”也占据一个 item 位置，配合适配器里对头部/尾部数量的扣减，
     * 保证多选时的 position 与游标位置对齐。
     */
    private void initResources() {
        mContentResolver = this.getContentResolver();
        mBackgroundQueryHandler = new BackgroundQueryHandler(this.getContentResolver());
        mCurrentFolderId = Notes.ID_ROOT_FOLDER;
        mNotesListView = (ListView) findViewById(R.id.notes_list);
        // footer 不参与点击（第三个参数 false），只作为列表底部的视觉留白。
        mNotesListView.addFooterView(LayoutInflater.from(this).inflate(R.layout.note_list_footer, null),
                null, false);
        mNotesListView.setOnItemClickListener(new OnListItemClickListener());
        mNotesListView.setOnItemLongClickListener(this);
        mNotesListAdapter = new NotesListAdapter(this);
        mNotesListView.setAdapter(mNotesListAdapter);
        mAddNewNote = (Button) findViewById(R.id.btn_new_note);
        mAddNewNote.setOnClickListener(this);
        mAddNewNote.setOnTouchListener(new NewNoteOnTouchListener());
        mDispatch = false;
        mDispatchY = 0;
        mOriginY = 0;
        mTitleBar = (TextView) findViewById(R.id.tv_title_bar);
        mState = ListEditState.NOTE_LIST;
        mModeCallBack = new ModeCallback();
    }

    /**
     * 多选模式（长按便签触发）的状态机回调。
     * 除了系统要求的 ActionMode 回调外，这里还挂了自定义下拉菜单（全选/取消全选）
     * 以及删除、移动两个菜单项的点击监听，实际业务动作在 onMenuItemClick 里。
     */
    private class ModeCallback implements ListView.MultiChoiceModeListener, OnMenuItemClickListener {
        private DropdownMenu mDropDownMenu;
        private ActionMode mActionMode;
        private MenuItem mMoveMenu;

        // 进入多选：加载操作菜单，并根据当前条目决定“移动”是否可用——
        // 通话记录里的便签没有可移动目标，用户一个文件夹都没有时也无处可移，两种情况都隐藏该项。
        // 同时隐藏“新建”按钮，避免与多选态并存产生歧义。
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.note_list_options, menu);
            menu.findItem(R.id.delete).setOnMenuItemClickListener(this);
            mMoveMenu = menu.findItem(R.id.move);
            if (mFocusNoteDataItem.getParentId() == Notes.ID_CALL_RECORD_FOLDER
                    || DataUtils.getUserFolderCount(mContentResolver) == 0) {
                mMoveMenu.setVisible(false);
            } else {
                mMoveMenu.setVisible(true);
                mMoveMenu.setOnMenuItemClickListener(this);
            }
            mActionMode = mode;
            mNotesListAdapter.setChoiceMode(true);
            mNotesListView.setLongClickable(false);
            mAddNewNote.setVisibility(View.GONE);

            View customView = LayoutInflater.from(NotesListActivity.this).inflate(
                    R.layout.note_list_dropdown_menu, null);
            mode.setCustomView(customView);
            mDropDownMenu = new DropdownMenu(NotesListActivity.this,
                    (Button) customView.findViewById(R.id.selection_menu),
                    R.menu.note_list_dropdown);
            mDropDownMenu.setOnDropdownMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
                public boolean onMenuItemClick(MenuItem item) {
                    mNotesListAdapter.selectAll(!mNotesListAdapter.isAllSelected());
                    updateMenu();
                    return true;
                }

            });
            return true;
        }

        // 按已选数量刷新下拉标题，并在“已全选/未全选”之间切换菜单项文案与勾选态。
        private void updateMenu() {
            int selectedCount = mNotesListAdapter.getSelectedCount();
            // Update dropdown menu
            String format = getResources().getString(R.string.menu_select_title, selectedCount);
            mDropDownMenu.setTitle(format);
            MenuItem item = mDropDownMenu.findItem(R.id.action_select_all);
            if (item != null) {
                if (mNotesListAdapter.isAllSelected()) {
                    item.setChecked(true);
                    item.setTitle(R.string.menu_deselect_all);
                } else {
                    item.setChecked(false);
                    item.setTitle(R.string.menu_select_all);
                }
            }
        }

        // 返回 false 表示菜单不需要在每次显示时重建；
        // 此处原为 IDE 生成的空实现，属已知可清理项。
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            // TODO Auto-generated method stub
            return false;
        }

        // 菜单项点击统一由 onCreateActionMode 里注册的 OnMenuItemClickListener 处理，
        // 因此这里保持空实现，两条路径不重复响应。
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            // TODO Auto-generated method stub
            return false;
        }

        // 退出多选：关闭适配器的选择模式、恢复长按、复原“新建”按钮，
        // 三处状态必须一起还原，否则会出现“按钮消失但仍是单选态”的错位。
        public void onDestroyActionMode(ActionMode mode) {
            mNotesListAdapter.setChoiceMode(false);
            mNotesListView.setLongClickable(true);
            mAddNewNote.setVisibility(View.VISIBLE);
        }

        public void finishActionMode() {
            mActionMode.finish();
        }

        // 单个条目勾选状态变化：写回适配器后立刻刷新菜单计数。
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id,
                boolean checked) {
            mNotesListAdapter.setCheckedItem(position, checked);
            updateMenu();
        }

        // 删除与移动的统一入口。没有任何选中项时先提示再返回，
        // 删除前二次确认并把选中数量展示给用户，避免误删。
        public boolean onMenuItemClick(MenuItem item) {
            if (mNotesListAdapter.getSelectedCount() == 0) {
                Toast.makeText(NotesListActivity.this, getString(R.string.menu_select_none),
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            switch (item.getItemId()) {
                case R.id.delete:
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(getString(R.string.alert_title_delete));
                    builder.setIcon(android.R.drawable.ic_dialog_alert);
                    builder.setMessage(getString(R.string.alert_message_delete_notes,
                                             mNotesListAdapter.getSelectedCount()));
                    builder.setPositiveButton(android.R.string.ok,
                                             new DialogInterface.OnClickListener() {
                                                 public void onClick(DialogInterface dialog,
                                                         int which) {
                                                     batchDelete();
                                                 }
                                             });
                    builder.setNegativeButton(android.R.string.cancel, null);
                    builder.show();
                    break;
                case R.id.move:
                    startQueryDestinationFolders();
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    /**
     * “新建便签”按钮的触摸拦截。
     * 该按钮底部有一块半透明区域，视觉上属于按钮、操作上应落到列表，
     * 所以这里做了一次坐标换算后把事件转发给 ListView；
     * 边界：仅在按压点位于透明区域且最后一项确实被按钮遮挡时才转发，其余情况不消费事件。
     */
    private class NewNoteOnTouchListener implements OnTouchListener {

        // 触摸总入口：按下时判断是否落在透明区并决定是否转发，
        // 移动与抬起时只负责把已转发的事件继续送给列表，不重新判定区域。
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    Display display = getWindowManager().getDefaultDisplay();
                    int screenHeight = display.getHeight();
                    int newNoteViewHeight = mAddNewNote.getHeight();
                    int start = screenHeight - newNoteViewHeight;
                    int eventY = start + (int) event.getY();
                    /**
                     * Minus TitleBar's height
                     */
                    if (mState == ListEditState.SUB_FOLDER) {
                        eventY -= mTitleBar.getHeight();
                        start -= mTitleBar.getHeight();
                    }
                    /**
                     * HACKME:When click the transparent part of "New Note" button, dispatch
                     * the event to the list view behind this button. The transparent part of
                     * "New Note" button could be expressed by formula y=-0.12x+94（Unit:pixel）
                     * and the line top of the button. The coordinate based on left of the "New
                     * Note" button. The 94 represents maximum height of the transparent part.
                     * Notice that, if the background of the button changes, the formula should
                     * also change. This is very bad, just for the UI designer's strong requirement.
                     */
                    if (event.getY() < (event.getX() * (-0.12) + 94)) {
                        View view = mNotesListView.getChildAt(mNotesListView.getChildCount() - 1
                                - mNotesListView.getFooterViewsCount());
                        if (view != null && view.getBottom() > start
                                && (view.getTop() < (start + 94))) {
                            mOriginY = (int) event.getY();
                            mDispatchY = eventY;
                            event.setLocation(event.getX(), mDispatchY);
                            mDispatch = true;
                            return mNotesListView.dispatchTouchEvent(event);
                        }
                    }
                    break;
                }
                // 拖动阶段：用相对按下点的位移增量更新目标 y 坐标，
                // 这样即使按钮自身不动，列表也能收到连续的滚动事件。
                case MotionEvent.ACTION_MOVE: {
                    if (mDispatch) {
                        mDispatchY += (int) event.getY() - mOriginY;
                        event.setLocation(event.getX(), mDispatchY);
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
                // 抬起/取消阶段：补发一次同坐标事件让列表结束滚动，然后清掉转发标记，
                // 否则下一次点击会被误判为上次拖动的延续。
                default: {
                    if (mDispatch) {
                        event.setLocation(event.getX(), mDispatchY);
                        mDispatch = false;
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
            }
            return false;
        }

    };

    /**
     * 发起便签列表异步查询。
     * 根目录与子文件夹用两套 selection（见 ROOT_FOLDER_SELECTION / NORMAL_SELECTION），
     * 排序统一为“类型降序 + 修改时间降序”，让文件夹排在便签前面、同类按最新在前。
     * selectionArgs 只传当前文件夹 id，与上面条件里的占位符一一对应，不要漏传或错序。
     */
    private void startAsyncNotesListQuery() {
        String selection = (mCurrentFolderId == Notes.ID_ROOT_FOLDER) ? ROOT_FOLDER_SELECTION
                : NORMAL_SELECTION;
        // 四个参数依次是：令牌、cookie（此处不用）、URI、投影、条件、条件参数、排序。
        // 投影取自 NoteItemData.PROJECTION，必须与 NoteItemData 的列下标顺序保持一致。
        mBackgroundQueryHandler.startQuery(FOLDER_NOTE_LIST_QUERY_TOKEN, null,
                Notes.CONTENT_NOTE_URI, NoteItemData.PROJECTION, selection, new String[] {
                    String.valueOf(mCurrentFolderId)
                }, NoteColumns.TYPE + " DESC," + NoteColumns.MODIFIED_DATE + " DESC");
    }

    /**
     * 异步查询处理器：把 ContentResolver 的查询放到后台线程，
     * 查询完成后由 onQueryComplete 回到主线程分发，避免在 UI 线程做 IO。
     */
    private final class BackgroundQueryHandler extends AsyncQueryHandler {
        public BackgroundQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        // 按 token 分流两类结果：便签列表直接换游标；文件夹列表弹选择框。
        // 游标所有权在此移交（changeCursor 或对话框适配器），本方法不再 close。
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
                case FOLDER_NOTE_LIST_QUERY_TOKEN:
                    // changeCursor 内部会关闭旧游标并触发重新查询，因此这里不需要手动 close。
                    mNotesListAdapter.changeCursor(cursor);
                    break;
                case FOLDER_LIST_QUERY_TOKEN:
                    // 空结果不弹框：没有可移动目标时静默失败，只留日志。
                    if (cursor != null && cursor.getCount() > 0) {
                        showFolderListMenu(cursor);
                    } else {
                        Log.e(TAG, "Query folder failed");
                    }
                    break;
                default:
                    return;
            }
        }
    }

    // 把“已选便签移到哪个文件夹”的选择框。点击后调用 DataUtils.batchMoveToFolder 批量移动，
    // 随后提示移动条数与目标文件夹名，并结束多选态。
    private void showFolderListMenu(Cursor cursor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
        // 适配器直接用查询结果构造，游标所有权随对话框转移，关闭时由系统回收。
        builder.setTitle(R.string.menu_title_select_folder);
        final FoldersListAdapter adapter = new FoldersListAdapter(this, cursor);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                // 批量移动直接操作数据库，一次事务内完成；移动后会触发 ContentObserver，列表自动刷新。
                DataUtils.batchMoveToFolder(mContentResolver,
                        mNotesListAdapter.getSelectedItemIds(), adapter.getItemId(which));
                Toast.makeText(
                        NotesListActivity.this,
                        getString(R.string.format_move_notes_to_folder,
                                mNotesListAdapter.getSelectedCount(),
                                adapter.getFolderName(NotesListActivity.this, which)),
                        Toast.LENGTH_SHORT).show();
                mModeCallBack.finishActionMode();
            }
        });
        builder.show();
    }

    // 新建便签：用 ACTION_INSERT_OR_EDIT 交给编辑页，并把当前文件夹 id 带过去，
    // 保证在哪个文件夹里点“新建”就落在哪个文件夹；用 startActivityForResult 以便回来刷新。
    private void createNewNote() {
        Intent intent = new Intent(this, NoteEditActivity.class);
        // ACTION_INSERT_OR_EDIT 让编辑页自行区分「新建」与「编辑」，本类无需传状态标志。
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mCurrentFolderId);
        this.startActivityForResult(intent, REQUEST_CODE_NEW_NODE);
    }

    /**
     * 批量删除已选便签。两种模式行为不同，是本方法与 deleteFolder 的核心分支：
     * 未开启同步：直接物理删除；已开启同步：移动回收站文件夹，留给同步链路处理，
     * 避免本地删除被同步逻辑“复活”。
     * 删除后还要按选中项涉及的桌面小部件逐个发更新广播，否则小部件会继续显示已删便签。
     */
    private void batchDelete() {
        new AsyncTask<Void, Void, HashSet<AppWidgetAttribute>>() {
            protected HashSet<AppWidgetAttribute> doInBackground(Void... unused) {
                HashSet<AppWidgetAttribute> widgets = mNotesListAdapter.getSelectedWidget();
                // 分支依据是同步模式：开启同步时删除必须走回收站，
                // 否则下次同步会把本地已删的便签重新拉回来（远端仍存在）。
                if (!isSyncMode()) {
                    // if not synced, delete notes directly
                    if (DataUtils.batchDeleteNotes(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds())) {
                    } else {
                        Log.e(TAG, "Delete notes error, should not happens");
                    }
                } else {
                    // in sync mode, we'll move the deleted note into the trash
                    // folder
                    if (!DataUtils.batchMoveToFolder(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds(), Notes.ID_TRASH_FOLER)) {
                        Log.e(TAG, "Move notes to trash folder error, should not happens");
                    }
                }
                return widgets;
            }

            @Override
            protected void onPostExecute(HashSet<AppWidgetAttribute> widgets) {
                if (widgets != null) {
                    // 逐个刷新涉及的小部件。id 与类型非法表示该便签未绑定小部件，跳过即可。
                    for (AppWidgetAttribute widget : widgets) {
                        if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                                && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                            updateWidget(widget.widgetId, widget.widgetType);
                        }
                    }
                }
                mModeCallBack.finishActionMode();
            }
        }.execute();
    }

    // 删除文件夹，规则与批量删除一致（同步态进回收站）。
    // 边界：根目录不允许删除，命中即记日志返回；
    // 删除前先取出该文件夹内便签关联的小部件，删除后再刷新。
    private void deleteFolder(long folderId) {
        if (folderId == Notes.ID_ROOT_FOLDER) {
            // 根目录代表整个便签库，删除它等于清空数据，因此直接拒绝而不是走确认流程。
            Log.e(TAG, "Wrong folder id, should not happen " + folderId);
            return;
        }

        HashSet<Long> ids = new HashSet<Long>();
        ids.add(folderId);
        // 先取该文件夹内便签关联的小部件，此时便签尚未删除，取到的关联才完整。
        HashSet<AppWidgetAttribute> widgets = DataUtils.getFolderNoteWidget(mContentResolver,
                folderId);
        // 与批量删除相同的同步分支：同步态下文件夹也进回收站，保持远端可追溯。
        if (!isSyncMode()) {
            // if not synced, delete folder directly
            DataUtils.batchDeleteNotes(mContentResolver, ids);
        } else {
            // in sync mode, we'll move the deleted folder into the trash folder
            DataUtils.batchMoveToFolder(mContentResolver, ids, Notes.ID_TRASH_FOLER);
        }
        if (widgets != null) {
            for (AppWidgetAttribute widget : widgets) {
                if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                        && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                    updateWidget(widget.widgetId, widget.widgetType);
                }
            }
        }
    }

    // 打开便签：把便签 id 通过 EXTRA_UID 传给编辑页，ACTION_VIEW 表示查看/编辑已有便签。
    private void openNode(NoteItemData data) {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_UID, data.getId());
        this.startActivityForResult(intent, REQUEST_CODE_OPEN_NODE);
    }

    /**
     * 进入某个文件夹。三件事必须一起做：切换当前文件夹 id、切换界面状态、更新标题栏。
     * 通话记录文件夹是特例：状态单独标记且隐藏“新建”按钮（其内容由通话服务写入）；
     * 普通文件夹进入后标题栏显示文件夹名（取自 snippet），并允许继续返回上一级。
     */
    private void openFolder(NoteItemData data) {
        mCurrentFolderId = data.getId();
        // 先换数据再换状态：查询依赖 mCurrentFolderId，顺序颠倒会查到上一个文件夹的内容。
        startAsyncNotesListQuery();
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mState = ListEditState.CALL_RECORD_FOLDER;
            mAddNewNote.setVisibility(View.GONE);
        } else {
            mState = ListEditState.SUB_FOLDER;
        }
        // 通话记录文件夹的名称由资源固定给出，不读数据库里的 snippet。
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mTitleBar.setText(R.string.call_record_folder_name);
        } else {
            mTitleBar.setText(data.getSnippet());
        }
        mTitleBar.setVisibility(View.VISIBLE);
    }

    // 目前只有“新建便签”按钮注册到本监听，其他点击由列表项监听器处理。
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_new_note:
                createNewNote();
                break;
            default:
                break;
        }
    }

    // 弹出/收起软键盘。文件夹命名对话框需要在弹窗弹出时立刻聚焦输入框，
    // 因此用 toggleSoftInput 强制显示而不是依赖输入框焦点。
    private void showSoftInput() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void hideSoftInput(View view) {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    /**
     * 新建或重命名文件夹的对话框（create=true 为新建）。
     * 两个易错点：
     * 1) 确定按钮先禁用，靠 TextWatcher 跟随输入内容启停，避免提交空名字；
     * 2) 确认按钮不能直接用 setPositiveButton 的回调——那样无法在“名字重复”时阻止对话框关闭，
     * 所以先取到 button1 再挂自己的监听，重名时提示并把输入全选，让用户直接改写。
     * 重命名走 update（并置 LOCAL_MODIFIED=1 供同步识别），新建走 insert。
     */
    private void showCreateOrModifyFolderDialog(final boolean create) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        // 对话框视图复用 dialog_edit_text 布局，新建与重命名共用同一套界面。
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        final EditText etName = (EditText) view.findViewById(R.id.et_foler_name);
        showSoftInput();
        // 重命名分支：输入框预填当前文件夹名，标题改为「重命名」；
        // 焦点项为空说明长按数据已失效，此时直接返回，避免把空名字写库。
        if (!create) {
            if (mFocusNoteDataItem != null) {
                etName.setText(mFocusNoteDataItem.getSnippet());
                builder.setTitle(getString(R.string.menu_folder_change_name));
            } else {
                Log.e(TAG, "The long click data item is null");
                return;
            }
        } else {
            etName.setText("");
            builder.setTitle(this.getString(R.string.menu_create_folder));
        }

        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                hideSoftInput(etName);
            }
        });

        // 先 show 再取 button1：正负按钮由 Builder 在 show 之后才创建，提前取会拿到 null。
        final Dialog dialog = builder.setView(view).show();
        final Button positive = (Button)dialog.findViewById(android.R.id.button1);
        positive.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                hideSoftInput(etName);
                String name = etName.getText().toString();
                // 重名校验覆盖同级所有可见文件夹；命中后不关闭对话框，把输入全选方便直接改写。
                if (DataUtils.checkVisibleFolderName(mContentResolver, name)) {
                    Toast.makeText(NotesListActivity.this, getString(R.string.folder_exist, name),
                            Toast.LENGTH_LONG).show();
                    etName.setSelection(0, etName.length());
                    return;
                }
                if (!create) {
                    if (!TextUtils.isEmpty(name)) {
                        ContentValues values = new ContentValues();
                        values.put(NoteColumns.SNIPPET, name);
                        values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                        values.put(NoteColumns.LOCAL_MODIFIED, 1);
                        mContentResolver.update(Notes.CONTENT_NOTE_URI, values, NoteColumns.ID
                                + "=?", new String[] {
                            String.valueOf(mFocusNoteDataItem.getId())
                        });
                    }
                } else if (!TextUtils.isEmpty(name)) {
                    ContentValues values = new ContentValues();
                    values.put(NoteColumns.SNIPPET, name);
                    values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                    mContentResolver.insert(Notes.CONTENT_NOTE_URI, values);
                }
                dialog.dismiss();
            }
        });

        // 初始为空时先禁用确定按钮；TextWatcher 只在内容变化时切换，不改变已有状态。
        if (TextUtils.isEmpty(etName.getText())) {
            positive.setEnabled(false);
        }
        /**
         * When the name edit text is null, disable the positive button
         */
        etName.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // TODO Auto-generated method stub

            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (TextUtils.isEmpty(etName.getText())) {
                    positive.setEnabled(false);
                } else {
                    positive.setEnabled(true);
                }
            }

            public void afterTextChanged(Editable s) {
                // TODO Auto-generated method stub

            }
        });
    }

    // 返回键按状态处理：子文件夹/通话记录先退回根目录并重新查询，
    // 只有根目录状态才真正退出 Activity，避免用户误退出应用。
    @Override
    public void onBackPressed() {
        // 状态机的出口只有三处，注意 CALL_RECORD_FOLDER 需要额外恢复「新建」按钮，
        // 因为进入该状态时它被隐藏过。
        switch (mState) {
            case SUB_FOLDER:
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                startAsyncNotesListQuery();
                mTitleBar.setVisibility(View.GONE);
                break;
            case CALL_RECORD_FOLDER:
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                mAddNewNote.setVisibility(View.VISIBLE);
                mTitleBar.setVisibility(View.GONE);
                startAsyncNotesListQuery();
                break;
            case NOTE_LIST:
                super.onBackPressed();
                break;
            default:
                break;
        }
    }

    // 刷新桌面小部件：按小部件类型选择对应 Provider，带上 id 发更新广播；
    // 同时 setResult 通知调用方数据已变化。类型不合法时只记日志，不发广播。
    private void updateWidget(int appWidgetId, int appWidgetType) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        // 只支持 2x/4x 两种小部件；类型不符时直接返回，避免发出无人接收的广播。
        if (appWidgetType == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (appWidgetType == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        // 广播只带一个 id，避免误刷其他小部件；若一次涉及多个，调用方会循环调用本方法。
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
            appWidgetId
        });

        // 发广播让 Provider 立即重绘，setResult 则通知调用方数据已变化，两条路径互不替代。
        sendBroadcast(intent);
        setResult(RESULT_OK, intent);
    }

    // 文件夹长按菜单：查看、删除、重命名。菜单项 id 与 onContextItemSelected 里的分支一一对应。
    private final OnCreateContextMenuListener mFolderOnCreateContextMenuListener = new OnCreateContextMenuListener() {
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
            if (mFocusNoteDataItem != null) {
                menu.setHeaderTitle(mFocusNoteDataItem.getSnippet());
                menu.add(0, MENU_FOLDER_VIEW, 0, R.string.menu_folder_view);
                menu.add(0, MENU_FOLDER_DELETE, 0, R.string.menu_folder_delete);
                menu.add(0, MENU_FOLDER_CHANGE_NAME, 0, R.string.menu_folder_change_name);
            }
        }
    };

    @Override
    public void onContextMenuClosed(Menu menu) {
        if (mNotesListView != null) {
            mNotesListView.setOnCreateContextMenuListener(null);
        }
        super.onContextMenuClosed(menu);
    }

    // 文件夹长按菜单的分发。删除前二次确认；当前焦点项为空时直接返回 false，
    // 这种情况只会出现在上下文菜单关闭后仍收到回调的极端时序。
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (mFocusNoteDataItem == null) {
            Log.e(TAG, "The long click data item is null");
            return false;
        }
        switch (item.getItemId()) {
            // 查看文件夹会进入下一级列表；其余两项分别走确认删除与重命名对话框。
            case MENU_FOLDER_VIEW:
                openFolder(mFocusNoteDataItem);
                break;
            case MENU_FOLDER_DELETE:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_folder));
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                deleteFolder(mFocusNoteDataItem.getId());
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
                break;
            case MENU_FOLDER_CHANGE_NAME:
                showCreateOrModifyFolderDialog(false);
                break;
            default:
                break;
        }

        return true;
    }

    // 选项菜单按界面状态切换：根目录、子文件夹、通话记录各一套菜单资源。
    // 根目录下还要根据同步服务当前是否在跑，把“同步”项在“同步/取消同步”之间切换。
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        if (mState == ListEditState.NOTE_LIST) {
            getMenuInflater().inflate(R.menu.note_list, menu);
            // set sync or sync_cancel
            menu.findItem(R.id.menu_sync).setTitle(
                    GTaskSyncService.isSyncing() ? R.string.menu_sync_cancel : R.string.menu_sync);
        } else if (mState == ListEditState.SUB_FOLDER) {
            getMenuInflater().inflate(R.menu.sub_folder, menu);
        } else if (mState == ListEditState.CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_record_folder, menu);
        } else {
            Log.e(TAG, "Wrong state:" + mState);
        }
        return true;
    }

    /**
     * 选项菜单分发。三处值得注意：
     * 1) 同步项在已配置账户时直接启停同步服务，未配置账户时跳设置页引导配置；
     * 2) 导出文本走 AsyncTask（BackupUtils.exportToText），三态结果分别提示；
     * 3) 搜索不自己实现，交给系统 SearchManager（见 onSearchRequested）。
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_new_folder: {
                showCreateOrModifyFolderDialog(true);
                break;
            }
            case R.id.menu_export_text: {
                exportNoteToText();
                break;
            }
            case R.id.menu_sync: {
                if (isSyncMode()) {
                    if (TextUtils.equals(item.getTitle(), getString(R.string.menu_sync))) {
                        GTaskSyncService.startSync(this);
                    } else {
                        GTaskSyncService.cancelSync(this);
                    }
                } else {
                    startPreferenceActivity();
                }
                break;
            }
            case R.id.menu_setting: {
                startPreferenceActivity();
                break;
            }
            case R.id.menu_new_note: {
                createNewNote();
                break;
            }
            case R.id.menu_search:
                onSearchRequested();
                break;
            default:
                break;
        }
        return true;
    }

    // 调用系统搜索：searchable 配置在 AndroidManifest 的 meta-data 中，
    // 查询最终由 NotesProvider 的 searchSuggestions 处理；
    // 第一个参数传 null 表示使用默认查询串，false 表示不自动拉起输入法。
    @Override
    public boolean onSearchRequested() {
        startSearch(null, false, null /* appData */, false);
        return true;
    }

    // 导出全部便签到 SD 卡文本文件。IO 与写文件都在后台线程完成，
    // 主线程只根据返回码（SD 卡未挂载/成功/系统错误）给出提示，成功时附带文件路径。
    private void exportNoteToText() {
        final BackupUtils backup = BackupUtils.getInstance(NotesListActivity.this);
        new AsyncTask<Void, Void, Integer>() {

            @Override
            protected Integer doInBackground(Void... unused) {
                return backup.exportToText();
            }

            @Override
            protected void onPostExecute(Integer result) {
                // 三种返回码对应三种提示语：SD 卡未挂载、导出成功（附路径）、系统错误。
                if (result == BackupUtils.STATE_SD_CARD_UNMOUONTED) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.failed_sdcard_export));
                    builder.setMessage(NotesListActivity.this
                            .getString(R.string.error_sdcard_unmounted));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                // 成功分支要回显文件路径，用户才能找到导出的 txt；路径由 BackupUtils 统一给出。
                } else if (result == BackupUtils.STATE_SUCCESS) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.success_sdcard_export));
                    builder.setMessage(NotesListActivity.this.getString(
                            R.string.format_exported_file_location, backup
                                    .getExportedTextFileName(), backup.getExportedTextFileDir()));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                } else if (result == BackupUtils.STATE_SYSTEM_ERROR) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.failed_sdcard_export));
                    builder.setMessage(NotesListActivity.this
                            .getString(R.string.error_sdcard_export));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                }
            }

        }.execute();
    }

    // 是否处于同步模式：判据是设置里是否存了非空账户名，
    // 这直接决定删除操作是物理删除还是进回收站（见 batchDelete / deleteFolder）。
    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    // 打开设置页。若本 Activity 被 TabActivity 托管，则从父 Activity 启动，
    // 否则会出现同栈内重复实例；startActivityIfNeeded 进一步避免重复创建。
    private void startPreferenceActivity() {
        Activity from = getParent() != null ? getParent() : this;
        Intent intent = new Intent(from, NotesPreferenceActivity.class);
        from.startActivityIfNeeded(intent, -1);
    }

    /**
     * 列表项点击。两套语义按优先级分派：
     * 先看是否处于多选态——多选时只切换勾选（且只允许便签，文件夹与小部件不参与），
     * 注意此处要减去 header 数量再回传 position，否则勾选会错位；
     * 非多选态再按界面状态决定“进文件夹”还是“开便签”。
     */
    private class OnListItemClickListener implements OnItemClickListener {

        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (view instanceof NotesListItem) {
                NoteItemData item = ((NotesListItem) view).getItemData();
                // 多选态下点击只切勾选，不进详情页；且只有便签可勾选，
                // 文件夹的点击在多选态下被忽略，防止把整夹便签误删。
                if (mNotesListAdapter.isInChoiceMode()) {
                    if (item.getType() == Notes.TYPE_NOTE) {
                        // 多选模式下 ListView 带 header，勾选位置必须减去 header 数量才能与游标行对齐。
                        position = position - mNotesListView.getHeaderViewsCount();
                        mModeCallBack.onItemCheckedStateChanged(null, position, id,
                                !mNotesListAdapter.isSelectedItem(position));
                    }
                    return;
                }

                // 根目录允许进入文件夹；子文件夹与通话记录只允许打开便签，
                // 因此这里出现文件夹类型一律视为脏数据并记日志。
                switch (mState) {
                    case NOTE_LIST:
                        if (item.getType() == Notes.TYPE_FOLDER
                                || item.getType() == Notes.TYPE_SYSTEM) {
                            openFolder(item);
                        } else if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        } else {
                            Log.e(TAG, "Wrong note type in NOTE_LIST");
                        }
                        break;
                    case SUB_FOLDER:
                    case CALL_RECORD_FOLDER:
                        if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        } else {
                            Log.e(TAG, "Wrong note type in SUB_FOLDER");
                        }
                        break;
                    default:
                        break;
                }
            }
        }

    }

    // 查询可作为移动目标的文件夹。条件排除三类：回收站、当前文件夹自身、非文件夹类型；
    // 在非根目录时还要额外把根目录并进结果（允许把便签移回根目录）。
    // 拼接 OR 时必须给前半段加括号，否则 AND/OR 优先级会让条件失效。
    private void startQueryDestinationFolders() {
        String selection = NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>? AND " + NoteColumns.ID + "<>?";
        // 在子文件夹中需要把根目录并入可选项（便于移回根目录），
        // 而在根目录时不能再把根目录本身列为目标。
        selection = (mState == ListEditState.NOTE_LIST) ? selection:
            "(" + selection + ") OR (" + NoteColumns.ID + "=" + Notes.ID_ROOT_FOLDER + ")";

        // 目标文件夹查询同样走异步处理器，条件参数顺序为：类型、回收站 id、当前文件夹 id。
        mBackgroundQueryHandler.startQuery(FOLDER_LIST_QUERY_TOKEN,
                null,
                Notes.CONTENT_NOTE_URI,
                FoldersListAdapter.PROJECTION,
                selection,
                new String[] {
                        String.valueOf(Notes.TYPE_FOLDER),
                        String.valueOf(Notes.ID_TRASH_FOLER),
                        String.valueOf(mCurrentFolderId)
                },
                NoteColumns.MODIFIED_DATE + " DESC");
    }

    // 长按分派：便签进入多选模式（并触发一次震动反馈），文件夹则挂上上下文菜单监听。
    // 返回 false 表示不消费事件，长按后仍可继续走系统的其他处理。
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view instanceof NotesListItem) {
            mFocusNoteDataItem = ((NotesListItem) view).getItemData();
            // 只有便签能进入多选；startActionMode 返回 null 说明系统拒绝进入（如已有菜单占用），
            // 此时记日志而不改任何状态。
            if (mFocusNoteDataItem.getType() == Notes.TYPE_NOTE && !mNotesListAdapter.isInChoiceMode()) {
                if (mNotesListView.startActionMode(mModeCallBack) != null) {
                    mModeCallBack.onItemCheckedStateChanged(null, position, id, true);
                    mNotesListView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    Log.e(TAG, "startActionMode fails");
                }
            } else if (mFocusNoteDataItem.getType() == Notes.TYPE_FOLDER) {
                mNotesListView.setOnCreateContextMenuListener(mFolderOnCreateContextMenuListener);
            }
        }
        return false;
    }
}
