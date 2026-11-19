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

package net.micode.notes.model;

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.tool.ResourceParser.NoteBgResources;


// 便签编辑会话模型，组合正文设置和底层差量对象
// 生命周期由主键正文删除标志及脏集合共同决定，不使用显式状态枚举
// 监听器连接编辑界面与小部件提醒更新，存在调用时序约束
// 保存返回值未透传底层写入失败，状态图必须保留这种差异
public class WorkingNote {
    // Note for the working note
    private Note mNote;
    // Note Id
    private long mNoteId;
    // Note content
    private String mContent;
    // Note mode
    private int mMode;

    private long mAlertDate;

    private long mModifiedDate;

    private int mBgColorId;

    private int mWidgetId;

    private int mWidgetType;

    private long mFolderId;

    private Context mContext;

    private static final String TAG = "WorkingNote";

    private boolean mIsDeleted;

    private NoteSettingChangedListener mNoteSettingStatusListener;

    public static final String[] DATA_PROJECTION = new String[] {
            DataColumns.ID,
            DataColumns.CONTENT,
            DataColumns.MIME_TYPE,
            DataColumns.DATA1,
            DataColumns.DATA2,
            DataColumns.DATA3,
            DataColumns.DATA4,
    };

    public static final String[] NOTE_PROJECTION = new String[] {
            NoteColumns.PARENT_ID,
            NoteColumns.ALERTED_DATE,
            NoteColumns.BG_COLOR_ID,
            NoteColumns.WIDGET_ID,
            NoteColumns.WIDGET_TYPE,
            NoteColumns.MODIFIED_DATE
    };

    private static final int DATA_ID_COLUMN = 0;

    private static final int DATA_CONTENT_COLUMN = 1;

    private static final int DATA_MIME_TYPE_COLUMN = 2;

    private static final int DATA_MODE_COLUMN = 3;

    private static final int NOTE_PARENT_ID_COLUMN = 0;

    private static final int NOTE_ALERTED_DATE_COLUMN = 1;

    private static final int NOTE_BG_COLOR_ID_COLUMN = 2;

    private static final int NOTE_WIDGET_ID_COLUMN = 3;

    private static final int NOTE_WIDGET_TYPE_COLUMN = 4;

    private static final int NOTE_MODIFIED_DATE_COLUMN = 5;

    // New note construct
    // 初始化编辑会话持有的上下文及数据对象
    // 双参数构造器建立主键为零的新草稿，三参数构造器读取已有记录
    // 删除标记默认关闭，加载失败由加载方法抛出异常
    private WorkingNote(Context context, long folderId) {
        mContext = context;
        mAlertDate = 0;
        mModifiedDate = System.currentTimeMillis();
        mFolderId = folderId;
        mNote = new Note();
        mNoteId = 0;
        mIsDeleted = false;
        mMode = 0;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
    }

    // Existing note construct
    // 初始化编辑会话持有的上下文及数据对象
    // 双参数构造器建立主键为零的新草稿，三参数构造器读取已有记录
    // 删除标记默认关闭，加载失败由加载方法抛出异常
    private WorkingNote(Context context, long noteId, long folderId) {
        mContext = context;
        mNoteId = noteId;
        mFolderId = folderId;
        mIsDeleted = false;
        mNote = new Note();
        loadNote();
    }

    // 按便签主键查询父文件夹提醒背景和小部件设置
    // 查询返回空游标对象才抛错，零条记录未被单独拒绝
    // 读取主表后继续加载正文，两个查询之间没有事务快照保证
    private void loadNote() {
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId), NOTE_PROJECTION, null,
                null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                mFolderId = cursor.getLong(NOTE_PARENT_ID_COLUMN);
                mBgColorId = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);
                mWidgetId = cursor.getInt(NOTE_WIDGET_ID_COLUMN);
                mWidgetType = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);
                mAlertDate = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);
            }
            cursor.close();
        } else {
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        loadNoteData();
    }

    // 按便签主键查询关联数据行并遍历MIME类型
    // 正文类型恢复内容模式及正文主键，通话类型只恢复通话主键
    // 不识别的MIME只记日志；游标对象为空才抛错
    private void loadNoteData() {
        Cursor cursor = mContext.getContentResolver().query(Notes.CONTENT_DATA_URI, DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?", new String[] {
                    String.valueOf(mNoteId)
                }, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    if (DataConstants.NOTE.equals(type)) {
                        mContent = cursor.getString(DATA_CONTENT_COLUMN);
                        mMode = cursor.getInt(DATA_MODE_COLUMN);
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        mNote.setCallDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else {
                        Log.d(TAG, "Wrong note type with type:" + type);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        } else {
            Log.e(TAG, "No data with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note's data with id " + mNoteId);
        }
    }

    // 建立未持久化的编辑草稿并配置背景和小部件
    // 配置方法可能生成差量，但空正文的新便签仍不会保存
    public static WorkingNote createEmptyNote(Context context, long folderId, int widgetId,
            int widgetType, int defaultBgColorId) {
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);
        note.setWidgetId(widgetId);
        note.setWidgetType(widgetType);
        return note;
    }

    // 将已有主键交给加载构造器
    // 主表及数据表读取失败可能抛异常，不在此处吞掉
    public static WorkingNote load(Context context, long id) {
        return new WorkingNote(context, id, 0);
    }

    // 先通过删除状态正文和差量判断是否值得保存
    // 新草稿先取得主键，再调用底层差量同步
    // 底层syncNote返回值被忽略，因此true不保证写入全部成功
    // 仅在小部件标识和类型有效且监听器存在时通知刷新
    public synchronized boolean saveNote() {
        if (isWorthSaving()) {
            if (!existInDatabase()) {
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }

            mNote.syncNote(mContext, mNoteId);

            /**
             * Update widget content if there exist any widget of this note
             */
            if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                    && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                    && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
            }
            return true;
        } else {
            return false;
        }
    }

    // 只以主键大于零作为存在标志
    // 该判断不查询数据库，不能证明记录此刻仍然存在
    public boolean existInDatabase() {
        return mNoteId > 0;
    }

    // 已标删除的新旧便签一律不保存
    // 新便签正文为空时不保存；已有便签无差量时不保存
    // 这是组合条件推导的状态，不是独立存储的状态枚举
    private boolean isWorthSaving() {
        if (mIsDeleted || (!existInDatabase() && TextUtils.isEmpty(mContent))
                || (existInDatabase() && !mNote.isLocalModified())) {
            return false;
        } else {
            return true;
        }
    }

    // 保存编辑界面的设置变化监听器
    // 后续设置变更可能同步触发回调，调用方应避免重入副作用
    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        mNoteSettingStatusListener = l;
    }

    // 提醒时间变化时写主表差量
    // 监听器存在则无论时间是否变化都发送提醒回调
    // set参数只传给监听器，不参与时间字段的写入判断
    public void setAlertDate(long date, boolean set) {
        if (date != mAlertDate) {
            mAlertDate = date;
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    // 修改内存删除标志，阻止后续正常保存
    // 当前方法不直接删除数据库记录，也允许用false撤销标记
    // 存在有效小部件时通知监听器刷新展示
    public void markDeleted(boolean mark) {
        mIsDeleted = mark;
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
        }
    }

    // 背景变化时更新会话字段并通知监听器
    // 随后才记录主表差量，回调发生在差量登记之前
    public void setBgColorId(int id) {
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    // 模式变化时先通知监听器旧值和新值
    // 再更新会话字段并写入正文模式差量，需注意回调观察时序
    public void setCheckListMode(int mode) {
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    // 类型变化时更新会话字段并登记主表差量
    // 不在此方法直接刷新桌面小部件
    public void setWidgetType(int type) {
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    // 小部件编号变化时记录会话字段和主表差量
    // 编号有效性由保存通知条件判断，此处不校验
    public void setWidgetId(int id) {
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    // 利用空值安全比较避免无变化时产生正文差量
    // 内容真正变化才更新会话缓存并标记正文待写入
    public void setWorkingText(String text) {
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    // 登记通话时间号码以及通话文件夹主表差量
    // 此处没有同步修改会话中的mFolderId，需区分缓存与待写入值
    public void convertToCallNote(String phoneNumber, long callDate) {
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    // 只根据提醒时间是否大于零判断是否有提醒
    // 并不查询系统AlarmManager中的实际注册状态
    public boolean hasClockAlert() {
        return (mAlertDate > 0 ? true : false);
    }

    // 返回编辑会话内存中的正文
    // 尚未保存的修改也能通过这里读取
    public String getContent() {
        return mContent;
    }

    // 返回会话缓存的提醒时间
    // 零值用作无提醒的约定
    public long getAlertDate() {
        return mAlertDate;
    }

    // 返回创建或加载时缓存的修改时间
    // 设置差量时底层时间可能改变，本字段未同步刷新
    public long getModifiedDate() {
        return mModifiedDate;
    }

    // 把背景颜色下标转换为编辑区drawable资源
    // 索引边界由资源映射类决定，本方法不作防御检查
    public int getBgColorResId() {
        return NoteBgResources.getNoteBgResource(mBgColorId);
    }

    // 返回颜色下标而非drawable资源编号
    // 不能与getBgColorResId的结果混用
    public int getBgColorId() {
        return mBgColorId;
    }

    // 按同一颜色下标查找标题背景资源
    // 标题数组和正文数组必须保持颜色顺序一致
    public int getTitleBgResId() {
        return NoteBgResources.getNoteTitleBgResource(mBgColorId);
    }

    // 返回会话缓存的正文展示模式
    // 界面据此区分普通正文与清单模式
    public int getCheckListMode() {
        return mMode;
    }

    // 返回便签主表编号
    // 新草稿在首次创建前编号为零
    public long getNoteId() {
        return mNoteId;
    }

    // 返回当前会话缓存的父文件夹编号
    // 转换通话便签的差量可能先于本缓存更新
    public long getFolderId() {
        return mFolderId;
    }

    // 返回会话缓存的小部件实例编号
    // 无效编号应与系统INVALID_APPWIDGET_ID比较
    public int getWidgetId() {
        return mWidgetId;
    }

    // 返回会话缓存的小部件尺寸类型
    // 项目自定义无效类型不能当作正常尺寸使用
    public int getWidgetType() {
        return mWidgetType;
    }

    public interface NoteSettingChangedListener {
        /**
         * Called when the background color of current note has just changed
         */
        void onBackgroundColorChanged();

        /**
         * Called when user set clock
         */
        void onClockAlertChanged(long date, boolean set);

        /**
         * Call when user create note from widget
         */
        void onWidgetChanged();

        /**
         * Call when switch between check list mode and normal mode
         * @param oldMode is previous mode before change
         * @param newMode is new mode
         */
        void onCheckListModeChanged(int oldMode, int newMode);
    }
}
