/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.dto;

import lombok.Data;
import java.util.List;

@Data
public class CleanupRequest {

    private boolean all;

    private List<String> usernames;

    private boolean deleteDataDir;
}
