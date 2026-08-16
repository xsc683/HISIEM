package com.xscsiem.hsiem_platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 用户存储:infra/auth/users.yaml(文件 + Git,story-08 ADR-1)。 */
@Component
public class UserStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String file;

    public UserStore(@Value("${app.auth-users-file:infra/auth/users.yaml}") String file) {
        this.file = file;
    }

    public List<AuthUser> list() {
        File f = new File(file);
        if (!f.exists()) {
            return new ArrayList<>();
        }
        try {
            List<AuthUser> users = yamlMapper.readValue(f,
                    yamlMapper.getTypeFactory().constructCollectionType(List.class, AuthUser.class));
            return users == null ? new ArrayList<>() : users;
        } catch (IOException e) {
            throw new IllegalStateException("用户文件读取失败: " + file, e);
        }
    }

    public void save(List<AuthUser> users) {
        try {
            File f = new File(file);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            yamlMapper.writeValue(f, users);
        } catch (IOException e) {
            throw new IllegalStateException("用户文件保存失败: " + file, e);
        }
    }
}
