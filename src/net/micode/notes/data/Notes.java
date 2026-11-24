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

import android.net.Uri;
// 数据契约类：集中定义便签的 authority、URI、MIME 类型和表字段常量
// 其它包都通过这里的常量访问数据，避免各写各的字符串
public class Notes {
    // content provider 的 authority，uri 里的主机名部分
    public static final String AUTHORITY = "micode_notes";
    public static final String TAG = "Notes";
    // 类型常量：普通便签、文件夹、系统文件夹
    public static final int TYPE_NOTE     = 0;
    public static final int TYPE_FOLDER   = 1;
    public static final int TYPE_SYSTEM   = 2;

    // 系统文件夹的固定 id，都是 0 或负数，用来和用户文件夹区分
    public static final int ID_ROOT_FOLDER = 0;
    public static final int ID_TEMPARAY_FOLDER = -1;
    public static final int ID_CALL_RECORD_FOLDER = -2;
    public static final int ID_TRASH_FOLER = -3;

    // Intent 传参时用的 key，跨组件传值统一用这些常量
    public static final String INTENT_EXTRA_ALERT_DATE = "net.micode.notes.alert_date";
    public static final String INTENT_EXTRA_BACKGROUND_ID = "net.micode.notes.background_color_id";
    public static final String INTENT_EXTRA_WIDGET_ID = "net.micode.notes.widget_id";
    public static final String INTENT_EXTRA_WIDGET_TYPE = "net.micode.notes.widget_type";
    public static final String INTENT_EXTRA_FOLDER_ID = "net.micode.notes.folder_id";
    public static final String INTENT_EXTRA_CALL_DATE = "net.micode.notes.call_date";

    // 桌面小部件类型：-1 无效，0 是 2x，1 是 4x
    public static final int TYPE_WIDGET_INVALIDE      = -1;
    public static final int TYPE_WIDGET_2X            = 0;
    public static final int TYPE_WIDGET_4X            = 1;

    // 两种数据类型的 MIME 类型常量
    public static class DataConstants {
        public static final String NOTE = TextNote.CONTENT_ITEM_TYPE;
        public static final String CALL_NOTE = CallNote.CONTENT_ITEM_TYPE;
    }

    // 查询所有便签和文件夹的 uri
    public static final Uri CONTENT_NOTE_URI = Uri.parse("content://" + AUTHORITY + "/note");

    // 查询便签正文数据的 uri
    public static final Uri CONTENT_DATA_URI = Uri.parse("content://" + AUTHORITY + "/data");

    // note 表的字段名常量
    public interface NoteColumns {
        // 数据库自增主键，插入成功后才有值；0 和负数被系统文件夹占着，正常便签都大于 0
        public static final String ID = "_id";

        // 所属父级 id（便签的父是文件夹，文件夹的父是上一级文件夹），INTEGER(long)
        public static final String PARENT_ID = "parent_id";

        // 创建时间，INTEGER(long)，单位毫秒
        public static final String CREATED_DATE = "created_date";

        // 最后修改时间，INTEGER(long)
        public static final String MODIFIED_DATE = "modified_date";

        // 提醒时间，INTEGER(long)
        public static final String ALERTED_DATE = "alert_date";

        // 文件夹名或便签的文本内容摘要，TEXT
        public static final String SNIPPET = "snippet";

        // 关联的桌面小部件 id，INTEGER(long)
        public static final String WIDGET_ID = "widget_id";

        // 桌面小部件类型，INTEGER(long)
        public static final String WIDGET_TYPE = "widget_type";

        // 便签背景颜色 id，INTEGER(long)
        public static final String BG_COLOR_ID = "bg_color_id";

        // 是否有附件，文本便签没有附件，多媒体便签至少一个，INTEGER
        public static final String HAS_ATTACHMENT = "has_attachment";

        // 文件夹里的便签数量，INTEGER(long)
        public static final String NOTES_COUNT = "notes_count";

        // 记录类型：文件夹还是便签，INTEGER
        public static final String TYPE = "type";

        // 同步时对应远端的行 id，没同步过的便签这里是 0
        public static final String SYNC_ID = "sync_id";

        // 标记本地是否被修改过，INTEGER
        public static final String LOCAL_MODIFIED = "local_modified";

        // 移入临时文件夹之前的原始父级 id，INTEGER
        public static final String ORIGIN_PARENT_ID = "origin_parent_id";

        // Google Tasks 的远端任务 id，TEXT
        public static final String GTASK_ID = "gtask_id";

        // 版本号，每次修改 +1，用于同步冲突判断，INTEGER(long)
        public static final String VERSION = "version";
    }

    // data 表的字段名常量
    public interface DataColumns {
        // data 表自己的主键，和 note 表的 id 不是一回事
        public static final String ID = "_id";

        // 该行数据的 MIME 类型，TEXT
        public static final String MIME_TYPE = "mime_type";

        // 该数据所属便签的 id，INTEGER(long)
        public static final String NOTE_ID = "note_id";

        // 创建时间，INTEGER(long)
        public static final String CREATED_DATE = "created_date";

        // 最后修改时间，INTEGER(long)
        public static final String MODIFIED_DATE = "modified_date";

        // 数据内容，TEXT
        public static final String CONTENT = "content";

        // 通用字段，具体含义由 MIME 类型决定，整数类型
        public static final String DATA1 = "data1";

        // 通用字段，整数类型
        public static final String DATA2 = "data2";

        // 通用字段，文本类型
        public static final String DATA3 = "data3";

        // 通用字段，文本类型
        public static final String DATA4 = "data4";

        // 通用字段，文本类型
        public static final String DATA5 = "data5";
    }

    // 文本便签的专属常量，DATA1 被用作清单模式标记
    public static final class TextNote implements DataColumns {
        // 文本便签是否为清单模式，1 是清单，0 是普通模式，INTEGER
        public static final String MODE = DATA1;

        public static final int MODE_CHECK_LIST = 1;

        // 多条文本便签的 MIME 类型
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/text_note";

        // 单条文本便签的 MIME 类型
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/text_note";

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/text_note");
    }

    // 通话记录便签的专属常量，DATA1 存通话日期，DATA3 存号码
    public static final class CallNote implements DataColumns {
        // 通话日期，INTEGER(long)
        public static final String CALL_DATE = DATA1;

        // 电话号码，TEXT
        public static final String PHONE_NUMBER = DATA3;

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/call_note";

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/call_note";

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/call_note");
    }
}
