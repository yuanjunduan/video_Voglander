package io.github.lunasaw.voglander.manager.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import io.github.lunasaw.voglander.repository.mapper.CascadeOrgMapper;
import org.springframework.stereotype.Service;

@Service
public class CascadeOrgServiceImpl extends ServiceImpl<CascadeOrgMapper, CascadeOrgDO>
    implements CascadeOrgService {
}
