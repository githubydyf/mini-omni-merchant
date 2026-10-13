package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.agent.entity.CommerceActionRecord;
import com.omnimerchant.agent.mapper.CommerceActionRecordMapper;
import com.omnimerchant.agent.support.CurrentActor;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommerceActionAuditService {
    private final CommerceActionRecordMapper mapper;

    public CommerceActionRecord record(String source, Long id, String type, String event, String state,
            Long orderId, String note, String before, String after) {
        var row = new CommerceActionRecord();
        row.setTenantId(TenantContextHolder.get());
        row.setSource(source);
        row.setRequestId(id);
        row.setActionType(type);
        row.setEventType(event);
        row.setState(state);
        row.setExecutionKey("EXECUTED".equals(state) ? "LOCAL" : null);
        row.setActorId(CurrentActor.id());
        row.setOrderId(orderId);
        row.setNote(note == null ? null : note.substring(0, Math.min(note.length(), 1000)));
        row.setBeforeSnapshot(before);
        row.setAfterSnapshot(after);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return row;
    }

    public List<CommerceActionRecord> history(String source, Long id) {
        return mapper.selectList(query(source, id).orderByAsc(CommerceActionRecord::getId).last("LIMIT 200"));
    }

    public CommerceActionRecord execution(String source, Long id) {
        return mapper.selectOne(query(source, id).eq(CommerceActionRecord::getExecutionKey, "LOCAL").last("LIMIT 1"));
    }

    public CommerceActionRecord latestAttempt(String source, Long id) {
        return mapper.selectOne(query(source, id).eq(CommerceActionRecord::getEventType, "EXECUTION")
                .orderByDesc(CommerceActionRecord::getId).last("LIMIT 1"));
    }

    private LambdaQueryWrapper<CommerceActionRecord> query(String source, Long id) {
        return new LambdaQueryWrapper<CommerceActionRecord>().eq(CommerceActionRecord::getTenantId, TenantContextHolder.get())
                .eq(CommerceActionRecord::getSource, source).eq(CommerceActionRecord::getRequestId, id);
    }
}
