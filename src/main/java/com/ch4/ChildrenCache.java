package com.ch4;

import java.util.ArrayList;
import java.util.List;

//用于保存上次获得的从节点列表的本地缓存
public class ChildrenCache {

	protected List<String> children;

    ChildrenCache() {
        this.children = null;
    }

    ChildrenCache(List<String> children) {
        this.children = children;
    }

    List<String> getList() {
        return children;
    }

    //返回新加入的
    List<String> addedAndSet( List<String> newChildren) {
        ArrayList<String> diff = null;

        if(children == null) {
            diff = new ArrayList<String>(newChildren);
        } else {
            for(String s: newChildren) {
                if(!children.contains( s )) {
                    if(diff == null) {
                        diff = new ArrayList<String>();
                    }

                    diff.add(s);
                }
            }
        }
        this.children = newChildren;

        return diff;
    }

    //返回被删除的
    List<String> removedAndSet( List<String> newChildren) {
        List<String> diff = null;

        if(children != null) {
            for(String s: children) {
                if(!newChildren.contains( s )) {
                    if(diff == null) {
                        diff = new ArrayList<String>();
                    }

                    diff.add(s);
                }
            }
        }
        this.children = newChildren;

        return diff;
    }

}
