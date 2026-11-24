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

import android.content.Context;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;


/**
 * “移动到文件夹”选择框的适配器：只查两列（id 与名称），
 * 根目录在界面上显示为“上一级文件夹”而不是数据库里的空名称。
 * 与 NotesListAdapter 不同：本适配器的游标在对话框内一次性用完即弃，
 * 不参与列表刷新，也没有多选状态，因此实现上比 NotesListAdapter 简单得多。
 */
public class FoldersListAdapter extends CursorAdapter {
    // 投影与列下标必须成对维护：改一处不改另一处会取错列。
    public static final String [] PROJECTION = {
        NoteColumns.ID,
        NoteColumns.SNIPPET
    };

    public static final int ID_COLUMN   = 0;
    public static final int NAME_COLUMN = 1;

    // 构造即传入查询结果：游标由适配器接管，本类与调用方都不再单独 close。
    // 因此调用方必须先确认查询有结果，空游标传进来会让列表显示为空而不是报错。
    public FoldersListAdapter(Context context, Cursor c) {
        super(context, c);
        // TODO Auto-generated constructor stub
    }

    // 行视图用内部类 FolderListItem（单行文本），结构比便签行简单得多。
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return new FolderListItem(context);
    }

    // 绑定文件夹名：根目录特判为“上一级文件夹”，其余取 snippet。
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof FolderListItem) {
            // 根目录在数据库里没有可读名称，界面固定显示「上一级文件夹」。
            String folderName = (cursor.getLong(ID_COLUMN) == Notes.ID_ROOT_FOLDER) ? context
                    .getString(R.string.menu_move_parent_folder) : cursor.getString(NAME_COLUMN);
            ((FolderListItem) view).bind(folderName);
        }
    }

    // 移动完成后需要提示“移动到了哪个文件夹”，此处按位置反查名称供 Toast 使用。
    public String getFolderName(Context context, int position) {
        Cursor cursor = (Cursor) getItem(position);
        return (cursor.getLong(ID_COLUMN) == Notes.ID_ROOT_FOLDER) ? context
                .getString(R.string.menu_move_parent_folder) : cursor.getString(NAME_COLUMN);
    }

    // 内部行视图：只承载一个 TextView，随对话框销毁一并回收。
    private class FolderListItem extends LinearLayout {
        private TextView mName;

        public FolderListItem(Context context) {
            super(context);
            // 行布局在构造时 inflate：newView 只在没有可复用的 convertView 时被调用，
            // 之后由 ListView 回收复用，因此不要在 bindView 里再 inflate。
            inflate(context, R.layout.folder_list_item, this);
            mName = (TextView) findViewById(R.id.tv_folder_name);
        }

        public void bind(String name) {
            mName.setText(name);
        }
    }

}
