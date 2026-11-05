package io.github.lunasaw.voglander.manager.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.lunasaw.voglander.manager.service.CascadeClientService;
import io.github.lunasaw.voglander.repository.entity.CascadeClientDO;
import io.github.lunasaw.voglander.repository.mapper.CascadeClientMapper;
import org.springframework.stereotype.Service;

@Service
public class CascadeClientServiceImpl extends ServiceImpl<CascadeClientMapper, CascadeClientDO>
    implements CascadeClientService {
}
