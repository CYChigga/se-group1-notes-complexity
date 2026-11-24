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

package net.micode.notes.data;


import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;


// 内容提供者：对外暴露便签数据的增删改查，是 app 内部和 widget、搜索共用的数据入口
// 用 ContentProvider 而不是直接开放数据库，是为了让 widget、系统搜索这些外部入口
// 也能通过统一的 uri 访问数据，跟课上学的内容提供者用法一致
public class NotesProvider extends ContentProvider {
    // URI 匹配器，根据传入的 uri 判断走哪条处理逻辑
    private static final UriMatcher mMatcher;

    // 数据库帮助类，负责拿到可读写的 SQLiteDatabase
    private NotesDatabaseHelper mHelper;

    private static final String TAG = "NotesProvider";

    // 下面这些是 uri 匹配返回的代号，对应静态块里注册的规则
    // 代号本身没有含义，只要和 addURI 注册时一一对应就行
    private static final int URI_NOTE            = 1;
    private static final int URI_NOTE_ITEM       = 2;
    private static final int URI_DATA            = 3;
    private static final int URI_DATA_ITEM       = 4;

    private static final int URI_SEARCH          = 5;
    private static final int URI_SEARCH_SUGGEST  = 6;

    static {
        // 注册所有支持的 uri 格式，"/#" 表示后面跟一个数字 id
        // 没注册过的 uri 一律走 default 抛异常，避免悄悄查错表
        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mMatcher.addURI(Notes.AUTHORITY, "note", URI_NOTE);
        mMatcher.addURI(Notes.AUTHORITY, "note/#", URI_NOTE_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "data", URI_DATA);
        mMatcher.addURI(Notes.AUTHORITY, "data/#", URI_DATA_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "search", URI_SEARCH);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, URI_SEARCH_SUGGEST);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", URI_SEARCH_SUGGEST);
    }

    // 搜索建议的查询投影。x'0A' 是 SQLite 里的换行符，为了在结果里多显示内容，把标题和正文的换行和空格去掉
    // 每一列都要按系统约定的列名起别名：TEXT_1 是主标题、ICON_1 是图标
    // 这里把同一个 snippet 同时给了 TEXT_1 和 TEXT_2，主副标题显示一样的内容
    private static final String NOTES_SEARCH_PROJECTION = NoteColumns.ID + ","
        + NoteColumns.ID + " AS " + SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_1 + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_2 + ","
        + R.drawable.search_result + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1 + ","
        + "'" + Intent.ACTION_VIEW + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_ACTION + ","
        + "'" + Notes.TextNote.CONTENT_TYPE + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA;

    // 按摘要模糊搜索的完整 SQL，只搜便签、排除回收站里的内容
    // 排除回收站（ID_TRASH_FOLER=-3）和文件夹，只搜真正的便签
    private static String NOTES_SNIPPET_SEARCH_QUERY = "SELECT " + NOTES_SEARCH_PROJECTION
        + " FROM " + TABLE.NOTE
        + " WHERE " + NoteColumns.SNIPPET + " LIKE ?"
        + " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER
        + " AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE;

    // Provider 创建时初始化数据库帮助类，成功返回 true
    // getInstance 是单例，整个进程共用一个数据库连接
    @Override
    public boolean onCreate() {
        mHelper = NotesDatabaseHelper.getInstance(getContext());
        return true;
    }

    // 查询入口：根据 uri 类型分发到不同的查询逻辑
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Cursor c = null;
        SQLiteDatabase db = mHelper.getReadableDatabase();
        // 读操作拿 getReadableDatabase 就够了，写操作才需要 getWritableDatabase
        String id = null;
        switch (mMatcher.match(uri)) {
            // 不带 id 就是查全表，列表页第一次进来走这里
            // 文件夹和便签混在一起，靠 type 字段区分
            case URI_NOTE:
                c = db.query(TABLE.NOTE, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            // 查单条便签：从 uri 里取 id，拼成 id 相等的条件
                // id 直接拼进 SQL 而不是用占位符，因为 uri 里的 # 只匹配数字，注入风险可控
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE.NOTE, projection, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
            case URI_DATA:
                // data 表存的是便签正文，一条便签可能对应多行数据
                c = db.query(TABLE.DATA, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            // 查单条数据内容
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE.DATA, projection, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
            // 搜索和搜索建议走同一套逻辑
            case URI_SEARCH:
            case URI_SEARCH_SUGGEST:
                // 搜索查询不允许额外指定排序或投影，否则抛异常
                if (sortOrder != null || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" + "with this query");
                }

                String searchString = null;
                // 搜索建议的搜索词从 uri 路径里取，普通搜索从 pattern 参数取
                // 搜索建议是给系统全局搜索用的，列名必须符合 SearchManager 的约定
                if (mMatcher.match(uri) == URI_SEARCH_SUGGEST) {
                    if (uri.getPathSegments().size() > 1) {
                        searchString = uri.getPathSegments().get(1);
                    }
                } else {
                    searchString = uri.getQueryParameter("pattern");
                }

                // 关键词为空就直接返回空结果，不做无效查询
                if (TextUtils.isEmpty(searchString)) {
                    return null;
                }

                try {
                    // 包一层百分号做模糊匹配
                    searchString = String.format("%%%s%%", searchString);
                    c = db.rawQuery(NOTES_SNIPPET_SEARCH_QUERY,
                            new String[] { searchString });
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "got exception: " + ex.toString());
                    // 出错只记日志不抛出，返回 null 让搜索界面显示空结果
                }
                break;
            // 没匹配到任何已注册的 uri
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        // 查询游标设置通知 uri，数据变化时能自动通知监听者刷新
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    // 插入入口：支持往 note 表和 data 表插入
    // 插入失败时 db.insert 返回 -1，下面用 >0 判断是否成功，失败的插入不发通知
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        long dataId = 0, noteId = 0, insertedId = 0;
        switch (mMatcher.match(uri)) {
            // 插入便签，把返回的自增 id 同时记到 noteId
            case URI_NOTE:
                insertedId = noteId = db.insert(TABLE.NOTE, null, values);
                break;
            // 插入数据内容，先从 values 里取出它属于哪条便签
            case URI_DATA:
                if (values.containsKey(DataColumns.NOTE_ID)) {
                    noteId = values.getAsLong(DataColumns.NOTE_ID);
                } else {
                    Log.d(TAG, "Wrong data format without note id:" + values.toString());
                    // 没带 note_id 也照样插入了，只是不通知便签列表，这里其实是个隐患
                }
                insertedId = dataId = db.insert(TABLE.DATA, null, values);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        // 便签插入成功后，通知监听便签 uri 的观察者
        if (noteId > 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), null);
        }

        // 数据插入成功后，通知监听 data uri 的观察者
        if (dataId > 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId), null);
        }

        // 返回带 id 的 uri，调用方可以据此继续操作
        return ContentUris.withAppendedId(uri, insertedId);
    }

    // 删除入口
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean deleteData = false;
        switch (mMatcher.match(uri)) {
            // 批量删便签：强制加 id>0 条件，防止误删系统文件夹
            case URI_NOTE:
                selection = "(" + selection + ") AND " + NoteColumns.ID + ">0 ";
                // 用括号包住外部条件，防止 AND/OR 优先级把 id>0 的保护绕过去
                count = db.delete(TABLE.NOTE, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                // id 小于等于 0 的是系统文件夹，不允许删除
                long noteId = Long.valueOf(id);
                if (noteId <= 0) {
                    // 直接 break 返回 0，调用方看到删除数为 0 就知道没删成
                    break;
                }
                count = db.delete(TABLE.NOTE,
                        NoteColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                break;
            case URI_DATA:
                // 删正文数据不检查所属便签，靠调用方保证条件正确
                count = db.delete(TABLE.DATA, selection, selectionArgs);
                deleteData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                count = db.delete(TABLE.DATA,
                        DataColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                deleteData = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            // 只有真删掉了行才发通知，删 0 行不打扰观察者
            // 删的是内容数据时，还要额外通知便签 uri，让便签列表也刷新
            if (deleteData) {
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    // 更新入口
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean updateData = false;
        switch (mMatcher.match(uri)) {
            // 更新便签前先把 version +1，用于同步时判断变更
                // -1 表示不限定单条，按 selection 批量加版本
            case URI_NOTE:
                increaseNoteVersion(-1, selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                increaseNoteVersion(Long.valueOf(id), selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                break;
            case URI_DATA:
                // 更新正文不动 version，version 只在 note 表上维护
                count = db.update(TABLE.DATA, values, selection, selectionArgs);
                updateData = true;
                break;
            case URI_DATA_ITEM:
                // 单条正文更新，同样要通知便签 uri 让列表摘要跟着变
                id = uri.getPathSegments().get(1);
                count = db.update(TABLE.DATA, values, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                updateData = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if (count > 0) {
            // 和删除一样，改到数据才通知；updateData 时额外通知便签列表
            if (updateData) {
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    // 工具方法：把额外的查询条件拼到 id 条件后面，空的话就不拼
    private String parseSelection(String selection) {
        return (!TextUtils.isEmpty(selection) ? " AND (" + selection + ')' : "");
    }

    // 把指定便签（或满足条件的便签）的 version 字段 +1，用 SQL 直接执行
    // 同步冲突靠 version 判断：本地每改一次 +1，同步时发现远端版本更新就要先合并
    private void increaseNoteVersion(long id, String selection, String[] selectionArgs) {
        StringBuilder sql = new StringBuilder(120);
        sql.append("UPDATE ");
        sql.append(TABLE.NOTE);
        sql.append(" SET ");
        sql.append(NoteColumns.VERSION);
        sql.append("=" + NoteColumns.VERSION + "+1 ");

        // 只有指定了 id 或条件时才加 where，否则会把全表都 +1
        if (id > 0 || !TextUtils.isEmpty(selection)) {
            sql.append(" WHERE ");
        }
        if (id > 0) {
            sql.append(NoteColumns.ID + "=" + String.valueOf(id));
        }
        if (!TextUtils.isEmpty(selection)) {
            // 有 id 时条件要用 AND 拼上去，没有 id 就直接用条件
            String selectString = id > 0 ? parseSelection(selection) : selection;
            // 把条件里的 ? 占位符逐个替换成真实值（这里 args 来自外部查询，需注意注入）
            for (String args : selectionArgs) {
                selectString = selectString.replaceFirst("\\?", args);
            }
            sql.append(selectString);
        }

        mHelper.getWritableDatabase().execSQL(sql.toString());
    }

    // 返回 uri 对应的 MIME 类型。这里没有实现，恒返回 null
    // 原项目留的空实现之一：外部按 MIME 类型访问时会拿到 null
    // 这也是第 3 章代码质量里 TODO 空实现问题的一个实例
    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

}
