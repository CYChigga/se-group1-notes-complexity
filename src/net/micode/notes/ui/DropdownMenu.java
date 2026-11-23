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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;

import net.micode.notes.R;

/**
 * 下拉菜单封装：把一个 Button 和一个 PopupMenu 绑定起来，
 * 点击按钮弹出菜单，菜单标题写在按钮上（多选态下显示“已选择 N 项”）。
 * 抽出来的原因是多选 ActionMode 的自定义视图里需要复用同一套交互，
 * 避免在 Activity 中重复写 PopupMenu 的构造与监听逻辑。
 */
public class DropdownMenu {
    private Button mButton;
    private PopupMenu mPopupMenu;
    private Menu mMenu;

    // 构造时把按钮背景换成下拉指示样式，并把菜单资源 inflate 到 PopupMenu 中；
    // 点击事件在此一次性绑定，外部只需关心“选中了哪个菜单项”。
    public DropdownMenu(Context context, Button button, int menuId) {
        mButton = button;
        // 按钮背景换成下拉箭头样式，与 ActionMode 自定义视图的视觉保持一致。
        mButton.setBackgroundResource(R.drawable.dropdown_icon);
        mPopupMenu = new PopupMenu(context, mButton);
        mMenu = mPopupMenu.getMenu();
        mPopupMenu.getMenuInflater().inflate(menuId, mMenu);
        // 点击按钮只负责弹出菜单，业务响应全部由外部注入的监听器决定，本类不感知菜单语义。
        mButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mPopupMenu.show();
            }
        });
    }

    // 设置菜单项点击监听；菜单未创建时静默忽略，避免空指针。
    public void setOnDropdownMenuItemClickListener(OnMenuItemClickListener listener) {
        if (mPopupMenu != null) {
            mPopupMenu.setOnMenuItemClickListener(listener);
        }
    }

    // 透传 findItem 与 setTitle：调用方（多选回调）需要按 id 改菜单项文案与勾选态。
    public MenuItem findItem(int id) {
        return mMenu.findItem(id);
    }

    // setTitle 改的是按钮文案而非菜单标题，这是多选态显示已选数量的实现方式。
    public void setTitle(CharSequence title) {
        mButton.setText(title);
    }
}
