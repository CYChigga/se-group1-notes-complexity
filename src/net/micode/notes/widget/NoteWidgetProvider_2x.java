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
 * 2x2 规格的小部件：只覆写布局、背景与类型常量，更新逻辑全部继承自基类。
 */
public class NoteWidgetProvider_2x extends NoteWidgetProvider {
    @Override
    // 系统回调入口，直接转交基类处理，保证 2x / 4x 行为一致。
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.update(context, appWidgetManager, appWidgetIds);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.widget_2x;
    }

    @Override
    // 背景按便签配色映射到 2x 专用资源表，与 4x 的表不是同一套。
    protected int getBgResourceId(int bgId) {
        return ResourceParser.WidgetBgResources.getWidget2xBgResource(bgId);
    }

    @Override
    // 类型常量写进 Intent 一起传给编辑页，用于新建便签时回写正确的 widget_type。
    protected int getWidgetType() {
        return Notes.TYPE_WIDGET_2X;
    }
}
