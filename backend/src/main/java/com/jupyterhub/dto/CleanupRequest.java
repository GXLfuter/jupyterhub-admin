package com.jupyterhub.dto;

import lombok.Data;
import java.util.List;

/**
 * 清理任务请求DTO
 */
@Data
public class CleanupRequest {
    /**
     * 是否清理所有学生
     */
    private boolean all;

    /**
     * 要清理的学生用户名列表
     */
    private List<String> usernames;

    /**
     * 是否删除用户数据目录
     */
    private boolean deleteDataDir;
}
