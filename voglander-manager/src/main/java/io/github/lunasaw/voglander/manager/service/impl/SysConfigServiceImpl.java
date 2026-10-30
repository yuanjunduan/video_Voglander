package io.github.lunasaw.voglander.manager.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.lunasaw.voglander.manager.service.SysConfigService;
import io.github.lunasaw.voglander.repository.entity.SysConfigDO;
import io.github.lunasaw.voglander.repository.mapper.SysConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfigDO>
    implements SysConfigService {
}
