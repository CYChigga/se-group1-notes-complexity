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
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;

import java.util.ArrayList;


// 持久化差量对象，主表属性与正文通话属性分别缓存
// 编辑会话通过本类提交变化，不能把内存差量等同于已落库状态
// 插入和批次更新分多步执行，失败原子性需要结合Provider实现复核
public class Note {
    private ContentValues mNoteDiffValues;
    private NoteData mNoteData;
    private static final String TAG = "Note";
    /**
     * Create a new note id for adding a new note to databases
     */
    // 先插入便签主表并标记本地修改
    // 从返回URI解析新主键；解析失败时返回零
    // 仅对负一显式抛错，URI为空的异常并未覆盖
    public static synchronized long getNewNoteId(Context context, long folderId) {
        // Create a new note in the database
        // 新主表行至少需要时间类型父目录等基础属性，正文另在数据表存储
        ContentValues values = new ContentValues();
        long createdTime = System.currentTimeMillis();
        values.put(NoteColumns.CREATED_DATE, createdTime);
        values.put(NoteColumns.MODIFIED_DATE, createdTime);
        values.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
        // 创建即标记本地改动，便于远端同步发现新记录
        values.put(NoteColumns.LOCAL_MODIFIED, 1);
        values.put(NoteColumns.PARENT_ID, folderId);
        Uri uri = context.getContentResolver().insert(Notes.CONTENT_NOTE_URI, values);

        long noteId = 0;
        try {
            noteId = Long.valueOf(uri.getPathSegments().get(1));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Get note id error :" + e.toString());
            noteId = 0;
        }
        // 零与负一的错误处理不同，应保留实际返回和抛错语义
        if (noteId == -1) {
            throw new IllegalStateException("Wrong note id:" + noteId);
        }
        return noteId;
    }

    // 分别建立主表差量和正文通话差量容器
    // 此时不产生任何数据库写入
    public Note() {
        mNoteDiffValues = new ContentValues();
        mNoteData = new NoteData();
    }

    // 将键值加入主表待更新集合
    // 同时设置本地修改标志和当前修改时间
    public void setNoteValue(String key, String value) {
        mNoteDiffValues.put(key, value);
        mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
        mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
    }

    // 更新正文待写入字段
    // 内部实现同时触碰主表修改标志，保证同步能发现正文变化
    public void setTextData(String key, String value) {
        mNoteData.setTextData(key, value);
    }

    // 记录数据库中正文行的主键
    // 内部实现要求主键严格大于零，避免更新到无效记录
    public void setTextDataId(long id) {
        mNoteData.setTextDataId(id);
    }

    // 返回当前正文行主键
    // 零代表正文行尚未被插入或加载
    public long getTextDataId() {
        return mNoteData.mTextDataId;
    }

    // 记录通话附属数据行主键
    // 内部实现要求正数，主键不合法时立即抛出异常
    public void setCallDataId(long id) {
        mNoteData.setCallDataId(id);
    }

    // 更新通话附属记录的待写入字段
    // 内部实现同时触碰主表修改时间，正文和通话记录分开存储
    public void setCallData(String key, String value) {
        mNoteData.setCallData(key, value);
    }

    // 根据待写入集合是否非空判断脏状态
    // 外层同时检查主表与数据表，内层只检查正文及通话数据
    public boolean isLocalModified() {
        return mNoteDiffValues.size() > 0 || mNoteData.isLocalModified();
    }

    // 先校验便签主键，无修改则直接返回
    // 先尝试更新主表再推送数据表，主表零行更新仍继续执行
    // 该实现不是跨主表和数据表的单一事务，失败后差量恢复需另行设计
    public boolean syncNote(Context context, long noteId) {
        if (noteId <= 0) {
            throw new IllegalArgumentException("Wrong note id:" + noteId);
        }

        if (!isLocalModified()) {
            return true;
        }

        /**
         * In theory, once data changed, the note should be updated on {@link NoteColumns#LOCAL_MODIFIED} and
         * {@link NoteColumns#MODIFIED_DATE}. For data safety, though update note fails, we also update the
         * note data info
         */
        if (context.getContentResolver().update(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), mNoteDiffValues, null,
                null) == 0) {
            Log.e(TAG, "Update note error, should not happen");
            // Do not return, fall through
        }
        // 即使前面的主表更新零行，这里仍清空主表差量
        mNoteDiffValues.clear();

        if (mNoteData.isLocalModified()
                && (mNoteData.pushIntoContentResolver(context, noteId) == null)) {
            return false;
        }

        return true;
    }

    private class NoteData {
        private long mTextDataId;

        private ContentValues mTextDataValues;

        private long mCallDataId;

        private ContentValues mCallDataValues;

        private static final String TAG = "NoteData";

        // 初始化正文和通话记录的待写入集合
        // 零主键表示相应数据行尚未创建
        public NoteData() {
            mTextDataValues = new ContentValues();
            mCallDataValues = new ContentValues();
            mTextDataId = 0;
            mCallDataId = 0;
        }

        // 根据待写入集合是否非空判断脏状态
        // 外层同时检查主表与数据表，内层只检查正文及通话数据
        boolean isLocalModified() {
            return mTextDataValues.size() > 0 || mCallDataValues.size() > 0;
        }

        // 记录数据库中正文行的主键
        // 内部实现要求主键严格大于零，避免更新到无效记录
        void setTextDataId(long id) {
            if(id <= 0) {
                throw new IllegalArgumentException("Text data id should larger than 0");
            }
            mTextDataId = id;
        }

        // 记录通话附属数据行主键
        // 内部实现要求正数，主键不合法时立即抛出异常
        void setCallDataId(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("Call data id should larger than 0");
            }
            mCallDataId = id;
        }

        // 更新通话附属记录的待写入字段
        // 内部实现同时触碰主表修改时间，正文和通话记录分开存储
        void setCallData(String key, String value) {
            mCallDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        // 更新正文待写入字段
        // 内部实现同时触碰主表修改标志，保证同步能发现正文变化
        void setTextData(String key, String value) {
            mTextDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        // 正文和通话记录按已有主键选择插入或更新
        // 新数据直接insert，已有数据加入操作批次，二者不构成同一事务
        // 成功插入后清空差量；解析失败和远程异常可能返回空
        // 没有更新批次时也返回空，不能把空返回统一解释为零写入
        Uri pushIntoContentResolver(Context context, long noteId) {
            /**
             * Check for safety
             */
            if (noteId <= 0) {
                throw new IllegalArgumentException("Wrong note id:" + noteId);
            }

            ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
            ContentProviderOperation.Builder builder = null;

            // 只有正文差量非空才安排正文插入或更新
            if(mTextDataValues.size() > 0) {
                mTextDataValues.put(DataColumns.NOTE_ID, noteId);
                if (mTextDataId == 0) {
                    mTextDataValues.put(DataColumns.MIME_TYPE, TextNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mTextDataValues);
                    try {
                        setTextDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new text data fail with noteId" + noteId);
                        mTextDataValues.clear();
                        return null;
                    }
                } else {
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mTextDataId));
                    builder.withValues(mTextDataValues);
                    operationList.add(builder.build());
                }
                mTextDataValues.clear();
            }

            // 通话记录可以独立于正文存在自己的差量与主键
            if(mCallDataValues.size() > 0) {
                mCallDataValues.put(DataColumns.NOTE_ID, noteId);
                if (mCallDataId == 0) {
                    mCallDataValues.put(DataColumns.MIME_TYPE, CallNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mCallDataValues);
                    try {
                        setCallDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new call data fail with noteId" + noteId);
                        mCallDataValues.clear();
                        return null;
                    }
                } else {
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mCallDataId));
                    // 构建通话更新操作时复制待写入值，之后原差量会被清空
                    builder.withValues(mCallDataValues);
                    operationList.add(builder.build());
                }
                mCallDataValues.clear();
            }

            // 只有已有记录的更新进入批次，新记录的插入已在此前执行
            if (operationList.size() > 0) {
                try {
                    ContentProviderResult[] results = context.getContentResolver().applyBatch(
                            Notes.AUTHORITY, operationList);
                    return (results == null || results.length == 0 || results[0] == null) ? null
                            : ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId);
                } catch (RemoteException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                } catch (OperationApplicationException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                }
            }
            return null;
        }
    }
}
