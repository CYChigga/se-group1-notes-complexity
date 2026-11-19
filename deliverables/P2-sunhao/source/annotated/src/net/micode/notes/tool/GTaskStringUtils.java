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

// 集中声明Google Tasks同步协议字段和特殊列表约定
// 协议字面量同时被JSON读写和远端同步调用，修改会影响兼容性
// 这是常量契约类，无方法声明，统计不能为它虚构方法
public class GTaskStringUtils {

    // 单次同步动作的编号；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_ID = "action_id";

    // 请求内的动作集合；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_LIST = "action_list";

    // 动作类型字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_TYPE = "action_type";

    // 创建动作的协议值；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_TYPE_CREATE = "create";

    // 读取全量动作的协议值；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_TYPE_GETALL = "get_all";

    // 移动动作的协议值；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_TYPE_MOVE = "move";

    // 更新动作的协议值；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ACTION_TYPE_UPDATE = "update";

    // 创建者编号字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_CREATOR_ID = "creator_id";

    // 子实体集合字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_CHILD_ENTITY = "child_entity";

    // 同步客户端版本字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_CLIENT_VERSION = "client_version";

    // 远端任务完成标记；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_COMPLETED = "completed";

    // 当前列表编号；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_CURRENT_LIST_ID = "current_list_id";

    // 默认列表编号；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_DEFAULT_LIST_ID = "default_list_id";

    // 远端删除标记；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_DELETED = "deleted";

    // 移动目标列表；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_DEST_LIST = "dest_list";

    // 移动目标父实体；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_DEST_PARENT = "dest_parent";

    // 目标父实体类型；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_DEST_PARENT_TYPE = "dest_parent_type";

    // 实体变更增量；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ENTITY_DELTA = "entity_delta";

    // 实体类型字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ENTITY_TYPE = "entity_type";

    // 是否拉取已删除实体；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_GET_DELETED = "get_deleted";

    // 远端实体标识；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_ID = "id";

    // 实体顺序下标；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_INDEX = "index";

    // 远端修改时间；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_LAST_MODIFIED = "last_modified";

    // 最新同步检查点；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_LATEST_SYNC_POINT = "latest_sync_point";

    // 任务所属列表编号；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_LIST_ID = "list_id";

    // 远端列表集合；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_LISTS = "lists";

    // 实体名称或标题；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_NAME = "name";

    // 创建后返回的新标识；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_NEW_ID = "new_id";

    // 任务备注正文；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_NOTES = "notes";

    // 远端父实体编号；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_PARENT_ID = "parent_id";

    // 排序所依据的前一个同级实体；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_PRIOR_SIBLING_ID = "prior_sibling_id";

    // 请求返回结果集合；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_RESULTS = "results";

    // 移动来源列表；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_SOURCE_LIST = "source_list";

    // 任务集合；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_TASKS = "tasks";

    // 类型字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_TYPE = "type";

    // 列表实体的协议类型值；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_TYPE_GROUP = "GROUP";

    // 任务实体的协议类型值；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_TYPE_TASK = "TASK";

    // 用户信息字段；常量值须与同步端的字段约定一致
    public final static String GTASK_JSON_USER = "user";

    // 小米便签远端列表名称前缀，拼写沿用原协议以保持兼容；常量值须与同步端的字段约定一致
    public final static String MIUI_FOLDER_PREFFIX = "[MIUI_Notes]";

    // 默认列表的远端约定名称；常量值须与同步端的字段约定一致
    public final static String FOLDER_DEFAULT = "Default";

    // 通话记录列表约定名称；常量值须与同步端的字段约定一致
    public final static String FOLDER_CALL_NOTE = "Call_Note";

    // 同步元数据列表约定名称；常量值须与同步端的字段约定一致
    public final static String FOLDER_META = "METADATA";

    // 元数据中的远端编号字段；常量值须与同步端的字段约定一致
    public final static String META_HEAD_GTASK_ID = "meta_gid";

    // 主表元数据JSON对象键；常量值须与同步端的字段约定一致
    public final static String META_HEAD_NOTE = "meta_note";

    // 附属数据JSON数组键；常量值须与同步端的字段约定一致
    public final static String META_HEAD_DATA = "meta_data";

    // 提示用户不要编辑删除的元数据条目名称；常量值须与同步端的字段约定一致
    public final static String META_NOTE_NAME = "[META INFO] DON'T UPDATE AND DELETE";

}
