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
import android.text.format.DateUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser.NoteItemBgResources;


/**
 * 单行列表项视图（便签或文件夹）。一个视图要同时兼容四类内容：
 * 通话记录文件夹本身、通话记录里的条目、普通文件夹、普通便签，
 * 差异只体现在标题文案、副标题（来电人）、告警图标与背景样式上。
 */
public class NotesListItem extends LinearLayout {
    private ImageView mAlert;
    private TextView mTitle;
    private TextView mTime;
    private TextView mCallName;
    private NoteItemData mItemData;
    private CheckBox mCheckBox;

    // 构造时一次性取好子控件引用，bind 时只改属性不动布局层级，
    // 这是 ListView 复用的前提（避免每次绑定都 findViewById）。
    public NotesListItem(Context context) {
        super(context);
        inflate(context, R.layout.note_item, this);
        mAlert = (ImageView) findViewById(R.id.iv_alert_icon);
        mTitle = (TextView) findViewById(R.id.tv_title);
        mTime = (TextView) findViewById(R.id.tv_time);
        mCallName = (TextView) findViewById(R.id.tv_name);
        mCheckBox = (CheckBox) findViewById(android.R.id.checkbox);
    }

    /**
     * 把一条数据渲染到视图上，四个分支与数据层的四种条目类型一一对应。
     * 分支顺序：先判“通话记录文件夹本身”（用自身 id），再判“通话记录里的条目”（用父 id），
     * 两个条件互斥——通话记录文件夹的父节点是根目录，不会落进子条目分支，故顺序不影响结果；
     * 先写自身判定是为了让代码结构与 NoteItemData 的类型枚举保持同构，便于对照阅读。
     * 四类内容的差异：
     * - 通话记录文件夹：标题为固定的「通话记录」文案 + 条目数，图标换成通话图标，正文不显示来电人；
     * - 通话记录条目：副标题显示来电人，正文用二级文本样式，有提醒则显示时钟图标；
     * - 普通文件夹：标题为文件夹名 + 条目数，不显示告警图标；
     * - 普通便签：正文走 DataUtils.getFormattedSnippet 做截断与格式化，有提醒显示时钟。
     * 多选态下只有便签显示复选框，文件夹永远不参与多选。
     */
    public void bind(Context context, NoteItemData data, boolean choiceMode, boolean checked) {
        // 多选态下只有便签出现复选框，其余情况一律隐藏，防止文件夹被误勾选。
        if (choiceMode && data.getType() == Notes.TYPE_NOTE) {
            mCheckBox.setVisibility(View.VISIBLE);
            mCheckBox.setChecked(checked);
        } else {
            mCheckBox.setVisibility(View.GONE);
        }

        mItemData = data;
        // 先判文件夹自身再判其子条目：文件夹用 id 判定，子条目用 parentId 判定，两者互斥。
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mCallName.setVisibility(View.GONE);
            mAlert.setVisibility(View.VISIBLE);
            mTitle.setTextAppearance(context, R.style.TextAppearancePrimaryItem);
            mTitle.setText(context.getString(R.string.call_record_folder_name)
                    + context.getString(R.string.format_folder_files_count, data.getNotesCount()));
            mAlert.setImageResource(R.drawable.call_record);
        // 父 id 为通话记录文件夹的条目，副标题显示来电人，正文用二级文本样式以区分层级。
        } else if (data.getParentId() == Notes.ID_CALL_RECORD_FOLDER) {
            mCallName.setVisibility(View.VISIBLE);
            mCallName.setText(data.getCallName());
            mTitle.setTextAppearance(context,R.style.TextAppearanceSecondaryItem);
            mTitle.setText(DataUtils.getFormattedSnippet(data.getSnippet()));
            if (data.hasAlert()) {
                mAlert.setImageResource(R.drawable.clock);
                mAlert.setVisibility(View.VISIBLE);
            } else {
                mAlert.setVisibility(View.GONE);
            }
        } else {
            mCallName.setVisibility(View.GONE);
            mTitle.setTextAppearance(context, R.style.TextAppearancePrimaryItem);

            if (data.getType() == Notes.TYPE_FOLDER) {
                mTitle.setText(data.getSnippet()
                        + context.getString(R.string.format_folder_files_count,
                                data.getNotesCount()));
                mAlert.setVisibility(View.GONE);
            } else {
                mTitle.setText(DataUtils.getFormattedSnippet(data.getSnippet()));
                if (data.hasAlert()) {
                    mAlert.setImageResource(R.drawable.clock);
                    mAlert.setVisibility(View.VISIBLE);
                } else {
                    mAlert.setVisibility(View.GONE);
                }
            }
        }
        // 修改时间统一交给 DateUtils 转成「几分钟前」这类相对描述，文案随系统语言变化。
        mTime.setText(DateUtils.getRelativeTimeSpanString(data.getModifiedDate()));

        setBackground(data);
    }

    // 背景样式取决于条目在列表中的位置：单条、首条、末条、中间条各用一套圆角背景，
    // 以保证连续便签看起来是一张卡片；文件夹统一用文件夹背景。
    // 判定依据来自 NoteItemData 的 isFirst/isLast/isSingle 等派生属性。
    private void setBackground(NoteItemData data) {
        int id = data.getBgColorId();
        // 便签按位置选圆角背景，文件夹用统一背景，二者不共用资源。
        if (data.getType() == Notes.TYPE_NOTE) {
            if (data.isSingle() || data.isOneFollowingFolder()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgSingleRes(id));
            } else if (data.isLast()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgLastRes(id));
            } else if (data.isFirst() || data.isMultiFollowingFolder()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgFirstRes(id));
            } else {
                setBackgroundResource(NoteItemBgResources.getNoteBgNormalRes(id));
            }
        } else {
            setBackgroundResource(NoteItemBgResources.getFolderBgRes());
        }
    }

    // 供点击/长按回调取回该行数据，避免再从游标重复解析。
    public NoteItemData getItemData() {
        return mItemData;
    }
}
