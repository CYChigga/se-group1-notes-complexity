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

package net.micode.notes.gtask.data;

import android.database.Cursor;

import org.json.JSONObject;

/**
 * 同步节点的抽象基类：Task（便签）与 TaskList（目录）共用的远端实体身份。
 * 持有远端 gid、名称、远端最后修改时间与删除标记，并强制子类回答三个问题：
 * 如何生成 create/update 报文、如何与本地 JSON 互转、本次同步该采取什么动作。
 * 判定采用“时间戳比对”而非逐字段比对，一次 long 比较即可确认远端是否变化。
 */
public abstract class Node {
    // 一次同步对同一节点只允许一个动作；取值不连续，便于日志里一眼区分方向
    // （ADD / DEL / UPDATE 三组，各组再分 LOCAL / REMOTE，7 为双向冲突，8 为错误）。
    public static final int SYNC_ACTION_NONE = 0;

    public static final int SYNC_ACTION_ADD_REMOTE = 1;

    public static final int SYNC_ACTION_ADD_LOCAL = 2;

    public static final int SYNC_ACTION_DEL_REMOTE = 3;

    public static final int SYNC_ACTION_DEL_LOCAL = 4;

    public static final int SYNC_ACTION_UPDATE_REMOTE = 5;

    public static final int SYNC_ACTION_UPDATE_LOCAL = 6;

    public static final int SYNC_ACTION_UPDATE_CONFLICT = 7;

    public static final int SYNC_ACTION_ERROR = 8;

    private String mGid;

    private String mName;

    private long mLastModified;

    private boolean mDeleted;

    public Node() {
        mGid = null;
        mName = "";
        mLastModified = 0;
        mDeleted = false;
    }

    public abstract JSONObject getCreateAction(int actionId);

    public abstract JSONObject getUpdateAction(int actionId);

    public abstract void setContentByRemoteJSON(JSONObject js);

    public abstract void setContentByLocalJSON(JSONObject js);

    public abstract JSONObject getLocalJSONFromContent();

    public abstract int getSyncAction(Cursor c);

    public void setGid(String gid) {
        this.mGid = gid;
    }

    public void setName(String name) {
        this.mName = name;
    }

    public void setLastModified(long lastModified) {
        this.mLastModified = lastModified;
    }

    public void setDeleted(boolean deleted) {
        this.mDeleted = deleted;
    }

    public String getGid() {
        return this.mGid;
    }

    public String getName() {
        return this.mName;
    }

    public long getLastModified() {
        return this.mLastModified;
    }

    public boolean getDeleted() {
        return this.mDeleted;
    }

}
