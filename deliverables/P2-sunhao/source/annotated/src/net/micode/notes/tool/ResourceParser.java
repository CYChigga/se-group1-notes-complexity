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

package net.micode.notes.tool;

import android.content.Context;
import android.preference.PreferenceManager;

import net.micode.notes.R;
import net.micode.notes.ui.NotesPreferenceActivity;

// 把颜色字体和小部件尺寸等下标映射为Android资源
// 颜色数组的同序性是隐含契约，多处方法直接按下标访问
// 字体回退分支的返回语义与正常分支不同，需保留问题说明
public class ResourceParser {

    public static final int YELLOW           = 0;
    public static final int BLUE             = 1;
    public static final int WHITE            = 2;
    public static final int GREEN            = 3;
    public static final int RED              = 4;

    public static final int BG_DEFAULT_COLOR = YELLOW;

    public static final int TEXT_SMALL       = 0;
    public static final int TEXT_MEDIUM      = 1;
    public static final int TEXT_LARGE       = 2;
    public static final int TEXT_SUPER       = 3;

    public static final int BG_DEFAULT_FONT_SIZE = TEXT_MEDIUM;

    public static class NoteBgResources {
        // BG_EDIT_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_EDIT_RESOURCES = new int [] {
            R.drawable.edit_yellow,
            R.drawable.edit_blue,
            R.drawable.edit_white,
            R.drawable.edit_green,
            R.drawable.edit_red
        };

        // BG_EDIT_TITLE_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_EDIT_TITLE_RESOURCES = new int [] {
            R.drawable.edit_title_yellow,
            R.drawable.edit_title_blue,
            R.drawable.edit_title_white,
            R.drawable.edit_title_green,
            R.drawable.edit_title_red
        };

        // 以颜色下标查编辑区背景数组
        // 没有边界检查，调用方须保证下标合法
        public static int getNoteBgResource(int id) {
            return BG_EDIT_RESOURCES[id];
        }

        // 以颜色下标查标题背景数组
        // 该数组颜色顺序必须与编辑区背景一致
        public static int getNoteTitleBgResource(int id) {
            return BG_EDIT_TITLE_RESOURCES[id];
        }
    }

    // 偏好允许时随机选择有效背景下标
    // 否则返回默认黄色下标，不返回drawable编号
    public static int getDefaultBgId(Context context) {
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                NotesPreferenceActivity.PREFERENCE_SET_BG_COLOR_KEY, false)) {
            return (int) (Math.random() * NoteBgResources.BG_EDIT_RESOURCES.length);
        } else {
            return BG_DEFAULT_COLOR;
        }
    }

    public static class NoteItemBgResources {
        // BG_FIRST_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_FIRST_RESOURCES = new int [] {
            R.drawable.list_yellow_up,
            R.drawable.list_blue_up,
            R.drawable.list_white_up,
            R.drawable.list_green_up,
            R.drawable.list_red_up
        };

        // BG_NORMAL_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_NORMAL_RESOURCES = new int [] {
            R.drawable.list_yellow_middle,
            R.drawable.list_blue_middle,
            R.drawable.list_white_middle,
            R.drawable.list_green_middle,
            R.drawable.list_red_middle
        };

        // BG_LAST_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_LAST_RESOURCES = new int [] {
            R.drawable.list_yellow_down,
            R.drawable.list_blue_down,
            R.drawable.list_white_down,
            R.drawable.list_green_down,
            R.drawable.list_red_down,
        };

        // BG_SINGLE_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_SINGLE_RESOURCES = new int [] {
            R.drawable.list_yellow_single,
            R.drawable.list_blue_single,
            R.drawable.list_white_single,
            R.drawable.list_green_single,
            R.drawable.list_red_single
        };

        // 按颜色索引返回列表首项背景
        // 用于多项分组的顶部圆角展示
        public static int getNoteBgFirstRes(int id) {
            return BG_FIRST_RESOURCES[id];
        }

        // 按颜色索引返回列表末项背景
        // 用于多项分组的底部圆角展示
        public static int getNoteBgLastRes(int id) {
            return BG_LAST_RESOURCES[id];
        }

        // 按颜色索引返回独立单项背景
        // 不应与首项或末项的局部圆角资源混用
        public static int getNoteBgSingleRes(int id) {
            return BG_SINGLE_RESOURCES[id];
        }

        // 按颜色索引返回列表中间项背景
        // 数组未进行越界保护，非法颜色会抛异常
        public static int getNoteBgNormalRes(int id) {
            return BG_NORMAL_RESOURCES[id];
        }

        // 返回文件夹条目固定背景
        // 不随便签颜色下标变化
        public static int getFolderBgRes() {
            return R.drawable.list_folder;
        }
    }

    public static class WidgetBgResources {
        // BG_2X_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_2X_RESOURCES = new int [] {
            R.drawable.widget_2x_yellow,
            R.drawable.widget_2x_blue,
            R.drawable.widget_2x_white,
            R.drawable.widget_2x_green,
            R.drawable.widget_2x_red,
        };

        // 按颜色索引返回小尺寸桌面背景
        // 小部件尺寸与颜色是两个不同的选择维度
        public static int getWidget2xBgResource(int id) {
            return BG_2X_RESOURCES[id];
        }

        // BG_4X_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] BG_4X_RESOURCES = new int [] {
            R.drawable.widget_4x_yellow,
            R.drawable.widget_4x_blue,
            R.drawable.widget_4x_white,
            R.drawable.widget_4x_green,
            R.drawable.widget_4x_red
        };

        // 按颜色索引返回大尺寸桌面背景
        // 数组中的颜色顺序与小尺寸资源保持一致
        public static int getWidget4xBgResource(int id) {
            return BG_4X_RESOURCES[id];
        }
    }

    public static class TextAppearanceResources {
        // TEXTAPPEARANCE_RESOURCES按黄蓝白绿红或字体档位约定排列，使用处按下标读取
        private final static int [] TEXTAPPEARANCE_RESOURCES = new int [] {
            R.style.TextAppearanceNormal,
            R.style.TextAppearanceMedium,
            R.style.TextAppearanceLarge,
            R.style.TextAppearanceSuper
        };

        // 检查字体下标的上界后读取样式资源
        // 越界回退返回的是默认下标而非样式资源编号，存在类型语义不一致
        // 负下标未被防御检查，本次仅注释问题而不修改逻辑
        public static int getTexAppearanceResource(int id) {
            /**
             * HACKME: Fix bug of store the resource id in shared preference.
             * The id may larger than the length of resources, in this case,
             * return the {@link ResourceParser#BG_DEFAULT_FONT_SIZE}
             */
            if (id >= TEXTAPPEARANCE_RESOURCES.length) {
                return BG_DEFAULT_FONT_SIZE;
            }
            return TEXTAPPEARANCE_RESOURCES[id];
        }

        // 返回字体样式数组长度
        // 用于约束可选择的字体档位数量
        public static int getResourcesSize() {
            return TEXTAPPEARANCE_RESOURCES.length;
        }
    }
}
