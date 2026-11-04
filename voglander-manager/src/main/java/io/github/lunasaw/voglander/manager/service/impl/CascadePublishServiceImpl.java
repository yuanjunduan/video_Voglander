package io.github.lunasaw.voglander.manager.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import io.github.lunasaw.voglander.manager.service.CascadePublishService;
import io.github.lunasaw.voglander.repository.entity.CascadePublishDO;
import io.github.lunasaw.voglander.repository.mapper.CascadePublishMapper;

@Service
public class CascadePublishServiceImpl extends ServiceImpl<CascadePublishMapper, CascadePublishDO>
    implements CascadePublishService {
}
