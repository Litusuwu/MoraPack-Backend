package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSchema {
    private Integer id;
    private Integer userId;
    private String userName;
    private String userLastName;
    private String email;
    private TypeUser userType;
    private LocalDateTime loginTime;
    private LocalDateTime lastActivity;
    private Boolean active;
}
