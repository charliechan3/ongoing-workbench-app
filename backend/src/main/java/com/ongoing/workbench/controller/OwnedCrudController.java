package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.OwnedEntity;
import com.ongoing.workbench.repo.OwnedRepo;
import com.ongoing.workbench.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 归属资源 CRUD 基类：列表/新建/更新/删除全部限定为"当前登录用户"的数据。
 * 相比 CrudController（全局资源用），这里多一道 ownerId 归属与越权检查。
 */
public abstract class OwnedCrudController<T extends OwnedEntity, ID> {

    @Autowired
    protected OwnedRepo<T, ID> repo;

    @GetMapping
    public List<T> all() {
        return repo.findByOwnerId(uid());
    }

    @PostMapping
    public T create(@RequestBody T entity) {
        if (entity == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
        entity.setOwnerId(uid());
        return repo.save(entity);
    }

    @PutMapping("/{id}")
    public T update(@PathVariable ID id, @RequestBody T entity) {
        if (!repo.existsByIdAndOwnerId(id, uid())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权访问");
        }
        assignIdIfAbsent(entity, id);
        entity.setOwnerId(uid()); // 归属始终以服务端为准，防越权写入
        return repo.save(entity);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable ID id) {
        if (!repo.existsByIdAndOwnerId(id, uid())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权访问");
        }
        repo.deleteById(id);
    }

    protected String uid() {
        String u = UserContext.currentUserId();
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        return u;
    }

    /**
     * 请求体缺 id 时用路径 id 补上。否则 JPA 把实体当新记录 persist，
     * 而主键是手工赋值型（无 @GeneratedValue），会抛
     * "Identifier must be manually assigned before calling persist()" → 500。
     */
    @SuppressWarnings("unchecked")
    private void assignIdIfAbsent(T entity, ID id) {
        if (entity == null || id == null) return;
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(jakarta.persistence.Id.class)
                        || f.isAnnotationPresent(jakarta.persistence.EmbeddedId.class)) {
                    try {
                        f.setAccessible(true);
                        if (f.get(entity) == null) f.set(entity, id);
                    } catch (IllegalAccessException ignored) {
                    }
                    return;
                }
            }
        }
    }
}
