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

package net.micode.notes.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.ResourceParser;


/**
 * 4x4 规格的小部件：与 2x 的唯一差别是布局、背景资源表与类型常量。
 */
public class NoteWidgetProvider_4x extends NoteWidgetProvider {
    @Override
    // 系统回调入口，转交基类处理；此处不加逻辑，避免两套规格行为分叉。
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.update(context, appWidgetManager, appWidgetIds);
    }

    protected int getLayoutId() {
        return R.layout.widget_4x;
    }

    @Override
    // 4x 的背景资源表与 2x 分开维护，尺寸不同不能共用同一张图。
    protected int getBgResourceId(int bgId) {
        return ResourceParser.WidgetBgResources.getWidget4xBgResource(bgId);
    }

    @Override
    // 类型常量随点击 Intent 传给编辑页，新建时据此回写 widget_type。
    protected int getWidgetType() {
        return Notes.TYPE_WIDGET_4X;
    }
}
