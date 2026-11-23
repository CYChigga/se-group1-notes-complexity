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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;

import net.micode.notes.data.Notes;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;


/**
 * 便签/文件夹列表适配器，继承 CursorAdapter：数据源是 NotesProvider 查询出的游标，
 * 本类额外维护“多选状态”（位置→是否选中）与“便签总数”（用于全选判定）。
 * 关键约定：游标由本类持有并负责 close，外部只通过 changeCursor 交出所有权。
 */
public class NotesListAdapter extends CursorAdapter {
    private static final String TAG = "NotesListAdapter";
    // mSelectedIndex 只记录被操作过的位置（含取消选中），未出现的位置视为未选中；
    // mNotesCount 只统计 TYPE_NOTE 的条目，文件夹不计入，否则“全选”永远不成立。
    private Context mContext;
    // 位置到勾选状态的稀疏映射。切换多选模式时整体 clear，保证不残留上一次选择。
    private HashMap<Integer, Boolean> mSelectedIndex;
    // 便签总数（不含文件夹），只在数据变化或换游标时重算，供全选判定使用。
    private int mNotesCount;
    private boolean mChoiceMode;

    // 桌面小部件的标识对（id + 类型）。删除/移动便签后要据此逐个刷新小部件。
    public static class AppWidgetAttribute {
        public int widgetId;
        public int widgetType;
    };

    // 构造时游标传 null：数据由外部查询完成后再 changeCursor 注入。
    public NotesListAdapter(Context context) {
        super(context, null);
        mSelectedIndex = new HashMap<Integer, Boolean>();
        mContext = context;
        mNotesCount = 0;
    }

    // 列表项复用 NotesListItem（自定义 LinearLayout），内部再 inflate note_item 布局。
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return new NotesListItem(context);
    }

    // 绑定数据：每次都新建 NoteItemData 包装当前游标行，再交给列表项渲染。
    // 这里把“是否多选”“是否选中”一并传入，列表项据此决定复选框的可见与勾选态，
    // 因此切换多选模式后不需要重建视图，只靠 notifyDataSetChanged 即可。
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof NotesListItem) {
            NoteItemData itemData = new NoteItemData(context, cursor);
            // 四个入参分别是上下文、条目数据、是否多选、当前是否选中；
            // 列表项不持有游标，只持有解析后的 NoteItemData，避免视图层直接依赖 Cursor。
            ((NotesListItem) view).bind(context, itemData, mChoiceMode,
                    isSelectedItem(cursor.getPosition()));
        }
    }

    // 记录单个位置的勾选状态并立即刷新。用 HashMap 而非布尔数组，
    // 是因为位置会随查询结果变化，稀疏存储更省内存也不会越界。
    public void setCheckedItem(final int position, final boolean checked) {
        mSelectedIndex.put(position, checked);
        notifyDataSetChanged();
    }

    public boolean isInChoiceMode() {
        return mChoiceMode;
    }

    // 进入/退出多选模式。进入时清空历史选择，避免上一次的残留影响本次操作。
    public void setChoiceMode(boolean mode) {
        mSelectedIndex.clear();
        mChoiceMode = mode;
    }

    // 全选/取消全选：只对便签生效（文件夹不可批量删除/移动），
    // 游标不可用（moveToPosition 返回 false）的位置直接跳过。
    public void selectAll(boolean checked) {
        Cursor cursor = getCursor();
        // 按游标行遍历而不是按视图遍历：屏幕外的条目也要被选中，否则「全选」只对可见项生效。
        for (int i = 0; i < getCount(); i++) {
            if (cursor.moveToPosition(i)) {
                if (NoteItemData.getNoteType(cursor) == Notes.TYPE_NOTE) {
                    setCheckedItem(i, checked);
                }
            }
        }
    }

    // 取出选中条目的 id 集合供批量删除/移动使用。
    // 根目录 id 属于异常数据（不应出现在可选中列表里），命中时只记日志并跳过。
    public HashSet<Long> getSelectedItemIds() {
        HashSet<Long> itemSet = new HashSet<Long>();
        // 遍历已记录的位置而非整个游标，只处理被显式勾选过的条目。
        for (Integer position : mSelectedIndex.keySet()) {
            if (mSelectedIndex.get(position) == true) {
                Long id = getItemId(position);
                if (id == Notes.ID_ROOT_FOLDER) {
                    Log.d(TAG, "Wrong item id, should not happen");
                } else {
                    itemSet.add(id);
                }
            }
        }

        return itemSet;
    }

    // 取出选中条目关联的小部件信息，供删除后刷新小部件使用。
    // 注意此处刻意不 close 游标——游标归适配器所有，提前关闭会让后续 getView 拿到失效游标；
    // getItem 返回 null 说明位置已失效，直接返回 null 由调用方跳过刷新。
    public HashSet<AppWidgetAttribute> getSelectedWidget() {
        HashSet<AppWidgetAttribute> itemSet = new HashSet<AppWidgetAttribute>();
        // 与小部件相关的取数同样只遍历已勾选位置；
        // 同一个小部件可能被多条便签引用，用 HashSet 去重后只刷一次。
        for (Integer position : mSelectedIndex.keySet()) {
            if (mSelectedIndex.get(position) == true) {
                Cursor c = (Cursor) getItem(position);
                // 位置失效（数据已变化）时返回 null，由调用方跳过小部件刷新，不抛异常。
                if (c != null) {
                    AppWidgetAttribute widget = new AppWidgetAttribute();
                    NoteItemData item = new NoteItemData(mContext, c);
                    widget.widgetId = item.getWidgetId();
                    widget.widgetType = item.getWidgetType();
                    itemSet.add(widget);
                    /**
                     * Don't close cursor here, only the adapter could close it
                     */
                } else {
                    Log.e(TAG, "Invalid cursor");
                    return null;
                }
            }
        }
        return itemSet;
    }

    // 统计选中数量。遍历 value 而不是 key，避免把“取消选中”的记录也算进来。
    public int getSelectedCount() {
        Collection<Boolean> values = mSelectedIndex.values();
        if (null == values) {
            return 0;
        }
        Iterator<Boolean> iter = values.iterator();
        int count = 0;
        while (iter.hasNext()) {
            if (true == iter.next()) {
                count++;
            }
        }
        return count;
    }

    // 是否全选：要求数量非 0 且等于便签总数（不含文件夹），空列表不算全选。
    public boolean isAllSelected() {
        int checkedCount = getSelectedCount();
        return (checkedCount != 0 && checkedCount == mNotesCount);
    }

    public boolean isSelectedItem(final int position) {
        if (null == mSelectedIndex.get(position)) {
            return false;
        }
        return mSelectedIndex.get(position);
    }

    // 数据变化（ContentObserver 触发）与换游标时都要重算便签总数，
    // 否则“全选/取消全选”的文案会与实际情况不符。
    @Override
    // ContentObserver 触发时重算：批量删除后列表变短，若不重算会残留旧的便签总数。
    protected void onContentChanged() {
        super.onContentChanged();
        calcNotesCount();
    }

    @Override
    public void changeCursor(Cursor cursor) {
        // 换游标是数据源变化的主路径，因此重算放在这里而不是 getCount 里，避免频繁调用时重复遍历。
        super.changeCursor(cursor);
        calcNotesCount();
    }

    // 重算便签总数。游标为空项时记日志并提前返回，保留已统计的部分结果，
    // 比抛异常更符合列表渲染“尽力而为”的语义。
    private void calcNotesCount() {
        mNotesCount = 0;
        // 逐行解析类型，文件夹与通话记录不计入便签数。
        for (int i = 0; i < getCount(); i++) {
            Cursor c = (Cursor) getItem(i);
            if (c != null) {
                if (NoteItemData.getNoteType(c) == Notes.TYPE_NOTE) {
                    mNotesCount++;
                }
            } else {
                Log.e(TAG, "Invalid cursor");
                return;
            }
        }
    }
}
