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

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;


// 数据库操作类：负责便签数据库的建表、触发器和版本升级
// 数据库里只有两张表，note 存便签和文件夹，data 存便签的正文内容
public class NotesDatabaseHelper extends SQLiteOpenHelper {
    // 数据库文件名，存放在应用私有目录下
    private static final String DB_NAME = "note.db";

    // 当前数据库版本号，升级时靠这个数字判断走哪条迁移逻辑
    private static final int DB_VERSION = 4;

    // 表名常量，其它地方直接用 TABLE.NOTE、TABLE.DATA，避免写错字符串
    public interface TABLE {
        // 便签表：一行是一条便签或一个文件夹
        public static final String NOTE = "note";

        // 数据表：一行是便签的一段内容，通过 note_id 关联到便签
        public static final String DATA = "data";
    }

    private static final String TAG = "NotesDatabaseHelper";

    // 单例实例，整个进程共用一个 Helper，避免重复打开数据库
    private static NotesDatabaseHelper mInstance;

    // 建 note 表的 SQL，字段定义和 Notes.NoteColumns 里的常量一一对应
    private static final String CREATE_NOTE_TABLE_SQL =
        "CREATE TABLE " + TABLE.NOTE + "(" +
            // 主键自增 id
            NoteColumns.ID + " INTEGER PRIMARY KEY," +
            // 父级 id，0 表示挂在根文件夹下
            NoteColumns.PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +
            // 提醒时间，0 表示没有提醒
            NoteColumns.ALERTED_DATE + " INTEGER NOT NULL DEFAULT 0," +
            // 背景颜色 id，0 是默认颜色
            NoteColumns.BG_COLOR_ID + " INTEGER NOT NULL DEFAULT 0," +
            // 创建时间，默认取当前时间戳（秒转毫秒）
            NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            // 是否有附件
            NoteColumns.HAS_ATTACHMENT + " INTEGER NOT NULL DEFAULT 0," +
            // 修改时间，同创建时间默认当前时间
            NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            // 文件夹下的便签数量，由触发器维护
            NoteColumns.NOTES_COUNT + " INTEGER NOT NULL DEFAULT 0," +
            // 摘要，文件夹存名字，便签存正文开头
            NoteColumns.SNIPPET + " TEXT NOT NULL DEFAULT ''," +
            // 记录类型，0 便签、1 文件夹、2 系统文件夹
            NoteColumns.TYPE + " INTEGER NOT NULL DEFAULT 0," +
            // 绑定的桌面小部件 id
            NoteColumns.WIDGET_ID + " INTEGER NOT NULL DEFAULT 0," +
            // 小部件尺寸类型
            NoteColumns.WIDGET_TYPE + " INTEGER NOT NULL DEFAULT -1," +
            // 同步 id
            NoteColumns.SYNC_ID + " INTEGER NOT NULL DEFAULT 0," +
            // 本地是否改动过，1 表示改了待同步
            NoteColumns.LOCAL_MODIFIED + " INTEGER NOT NULL DEFAULT 0," +
            // 移入临时文件夹前的原父级
            NoteColumns.ORIGIN_PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +
            // Google Tasks 任务 id，未同步时为空串
            NoteColumns.GTASK_ID + " TEXT NOT NULL DEFAULT ''," +
            // 版本号，每次修改加一
            NoteColumns.VERSION + " INTEGER NOT NULL DEFAULT 0" +
        ")";

    // 建 data 表的 SQL，data 表存便签的正文内容
    private static final String CREATE_DATA_TABLE_SQL =
        "CREATE TABLE " + TABLE.DATA + "(" +
            // 主键自增 id
            DataColumns.ID + " INTEGER PRIMARY KEY," +
            // MIME 类型，区分文本便签还是通话记录
            DataColumns.MIME_TYPE + " TEXT NOT NULL," +
            // 所属便签 id，关联 note 表
            DataColumns.NOTE_ID + " INTEGER NOT NULL DEFAULT 0," +
            // 创建时间
            NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            // 修改时间
            NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            // 正文内容
            DataColumns.CONTENT + " TEXT NOT NULL DEFAULT ''," +
            // 通用字段 1，整数，含义随 MIME 类型变化
            DataColumns.DATA1 + " INTEGER," +
            // 通用字段 2，整数
            DataColumns.DATA2 + " INTEGER," +
            // 通用字段 3，文本
            DataColumns.DATA3 + " TEXT NOT NULL DEFAULT ''," +
            // 通用字段 4，文本
            DataColumns.DATA4 + " TEXT NOT NULL DEFAULT ''," +
            // 通用字段 5，文本
            DataColumns.DATA5 + " TEXT NOT NULL DEFAULT ''" +
        ")";

    // data 表按 note_id 建索引，加快按便签查询内容的效率
    private static final String CREATE_DATA_NOTE_ID_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS note_id_index ON " +
        TABLE.DATA + "(" + DataColumns.NOTE_ID + ");";

    // 便签从一个文件夹移动到另一个文件夹时，新文件夹里的笔记数 +1
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER increase_folder_count_on_update "+
        " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
        "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
        " END";

    // 便签移出文件夹时，旧文件夹里的笔记数 -1
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER decrease_folder_count_on_update " +
        " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
        "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
        "  AND " + NoteColumns.NOTES_COUNT + ">0" + ";" +
        " END";

    // 往文件夹里插入新便签时，该文件夹的笔记数 +1
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER =
        "CREATE TRIGGER increase_folder_count_on_insert " +
        " AFTER INSERT ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
        "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
        " END";

    // 从文件夹删除便签时，该文件夹的笔记数 -1；只有 >0 才减，防止减成负数
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER =
        "CREATE TRIGGER decrease_folder_count_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
        "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
        "  AND " + NoteColumns.NOTES_COUNT + ">0;" +
        " END";

    // 插入文本类型数据时，同步更新 note 表的 snippet 字段，让列表能直接显示摘要
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER =
        "CREATE TRIGGER update_note_content_on_insert " +
        " AFTER INSERT ON " + TABLE.DATA +
        " WHEN new." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
        "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
        " END";

    // 文本数据被修改时，同样同步更新便签的摘要内容
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER update_note_content_on_update " +
        " AFTER UPDATE ON " + TABLE.DATA +
        " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
        "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
        " END";

    // 文本数据被删除时，把便签的摘要清空
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER =
        "CREATE TRIGGER update_note_content_on_delete " +
        " AFTER delete ON " + TABLE.DATA +
        " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=''" +
        "  WHERE " + NoteColumns.ID + "=old." + DataColumns.NOTE_ID + ";" +
        " END";

    // 删除便签时，级联删掉它名下的所有 data 内容，避免留下孤儿数据
    private static final String NOTE_DELETE_DATA_ON_DELETE_TRIGGER =
        "CREATE TRIGGER delete_data_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN" +
        "  DELETE FROM " + TABLE.DATA +
        "   WHERE " + DataColumns.NOTE_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    // 删除文件夹时，级联删掉它下面的所有便签
    private static final String FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER =
        "CREATE TRIGGER folder_delete_notes_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN" +
        "  DELETE FROM " + TABLE.NOTE +
        "   WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    // 文件夹被移进回收站时，把它名下的便签也一并移到回收站
    private static final String FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER =
        "CREATE TRIGGER folder_move_notes_on_trash " +
        " AFTER UPDATE ON " + TABLE.NOTE +
        " WHEN new." + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
        "  WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    // 构造方法：传入 context，用 DB_NAME 和 DB_VERSION 初始化父类
    public NotesDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    // 建 note 表，然后重建它的触发器，最后写入几个系统文件夹
    public void createNoteTable(SQLiteDatabase db) {
        db.execSQL(CREATE_NOTE_TABLE_SQL);
        reCreateNoteTableTriggers(db);
        createSystemFolder(db);
        Log.d(TAG, "note table has been created");
    }

    // 先删掉旧的触发器，再按最新定义重建，避免升级时新旧触发器并存
    private void reCreateNoteTableTriggers(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS delete_data_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS folder_delete_notes_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS folder_move_notes_on_trash");

        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_DELETE_DATA_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER);
        db.execSQL(FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER);
        db.execSQL(FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER);
    }

    // 初始化时写入四个系统文件夹：通话记录、根目录、临时、回收站
    // 它们的 id 是负数或 0，用来和普通用户文件夹区分开
    private void createSystemFolder(SQLiteDatabase db) {
        ContentValues values = new ContentValues();

        // 通话记录文件夹，专门放通话录音生成的便签
        values.put(NoteColumns.ID, Notes.ID_CALL_RECORD_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        // 根文件夹，是默认文件夹，所有便签默认放这里
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_ROOT_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        // 临时文件夹，移动便签时用来做中转
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TEMPARAY_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        // 回收站文件夹，删掉的便签先放这里
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    // 建 data 表，重建它的触发器，并建 note_id 索引
    public void createDataTable(SQLiteDatabase db) {
        db.execSQL(CREATE_DATA_TABLE_SQL);
        reCreateDataTableTriggers(db);
        db.execSQL(CREATE_DATA_NOTE_ID_INDEX_SQL);
        Log.d(TAG, "data table has been created");
    }

    // 重建 data 表相关的三个内容同步触发器
    private void reCreateDataTableTriggers(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_delete");

        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER);
    }

    // 单例获取：保证整个应用只有一个 Helper 实例，加锁防止多线程并发创建
    static synchronized NotesDatabaseHelper getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NotesDatabaseHelper(context);
        }
        return mInstance;
    }

    // 首次创建数据库时调用，依次建好 note 表和 data 表
    @Override
    public void onCreate(SQLiteDatabase db) {
        createNoteTable(db);
        createDataTable(db);
    }

    // 版本升级入口：根据 oldVersion 逐级迁移到 newVersion，不能跳级
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        boolean reCreateTriggers = false;
        boolean skipV2 = false;

        // v1 升 v2：重建表（v2 本身包含了升到 v3 的改动，所以跳过 v3）
        if (oldVersion == 1) {
            upgradeToV2(db);
            skipV2 = true; // this upgrade including the upgrade from v2 to v3
            oldVersion++;
        }

        // v2 升 v3：新增 gtask_id 字段和回收站文件夹，之后需要重建触发器
        if (oldVersion == 2 && !skipV2) {
            upgradeToV3(db);
            reCreateTriggers = true;
            oldVersion++;
        }

        // v3 升 v4：新增 version 字段，用于同步时的乐观锁
        if (oldVersion == 3) {
            upgradeToV4(db);
            oldVersion++;
        }

        // 只要动过表结构，就重建一遍触发器，确保触发器和最新表结构匹配
        if (reCreateTriggers) {
            reCreateNoteTableTriggers(db);
            reCreateDataTableTriggers(db);
        }

        // 迁移完成后版本号必须一致，否则说明有版本没处理到，直接抛异常
        if (oldVersion != newVersion) {
            throw new IllegalStateException("Upgrade notes database to version " + newVersion
                    + "fails");
        }
    }

    // v1 升 v2：旧表结构差异太大，直接删表重建，走最新的建表逻辑
    private void upgradeToV2(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.NOTE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.DATA);
        createNoteTable(db);
        createDataTable(db);
    }

    private void upgradeToV3(SQLiteDatabase db) {
        // 删掉 v1 时代遗留的、已经不再使用的三个触发器
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_update");
        // 给 note 表加一列 gtask_id，用于和 Google Tasks 同步时记录远端任务 id
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.GTASK_ID
                + " TEXT NOT NULL DEFAULT ''");
        // 补一个回收站系统文件夹
        ContentValues values = new ContentValues();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    // v3 升 v4：加 version 列，同步时每次修改 +1，用来判断本地和远端谁新
    private void upgradeToV4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.VERSION
                + " INTEGER NOT NULL DEFAULT 0");
    }
}
