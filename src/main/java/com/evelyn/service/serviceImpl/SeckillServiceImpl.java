package com.evelyn.service.serviceImpl;

import com.evelyn.dao.SeckillDao;
import com.evelyn.dao.SuccessKilledDao;
import com.evelyn.dto.Exposer;
import com.evelyn.dto.SeckillExecution;
import com.evelyn.enums.SeckillStateEnum;
import com.evelyn.exception.RepeatKillException;
import com.evelyn.exception.SeckillCloseException;
import com.evelyn.exception.SeckillException;
import com.evelyn.pojo.Seckill;
import com.evelyn.pojo.SuccessKilled;
import com.evelyn.service.SeckillService;
import org.apache.log4j.Logger;
import org.mybatis.logging.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * @Component:不知道是什么，就用这个
 * @Service :服务
 * @Dao
 * @Controller
 */

@Service
public class SeckillServiceImpl implements SeckillService {
    //日志
    private Logger logger = Logger.getLogger(SeckillServiceImpl.class);
    //加盐，为了加密，混淆md5,随便写
    private final String salt="addjidjigjeijgeoejei8eur8u8&#$$(@)";
    //对象
//    注入Service依赖 @Service,@Resource等
    @Autowired
    private SeckillDao seckillDao;
    @Autowired
    private SuccessKilledDao successKilledDao;

    private String getMD5(long seckillId){
        String base = seckillId+"/"+salt;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    /**
     * 查询所有秒杀记录
     * @return 所有秒杀商品
     */

    @Override
    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0, 100);
    }

    /**
     * 查询单个商品
     *
     * @param seckillId
     * @return
     */
    @Override
    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    /**
     * 秒杀开启时输出秒杀接口地址，否则输出系统时间和秒杀时间
     * 意思就是秒杀还没开始的时候是没有地址的
     *
     * @param seckillId
     */
    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        Seckill seckill = seckillDao.queryById(seckillId);
        if(seckill==null){
            return new Exposer(false,seckillId);
        }
        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
//        系统时间
        Date nowTime = new Date();
        if(startTime.getTime()>nowTime.getTime()||endTime.getTime()<nowTime.getTime()){
            return new Exposer(false,seckillId,nowTime.getTime(),startTime.getTime(),endTime.getTime());
        }
        //转化特定字符串的过程，不可逆，就算把这个转化后的结果显示给用户，用户也猜不出来到底是啥
        String md5=getMD5(seckillId);
        return new Exposer(true,md5,seckillId);
    }

    /**
     * 执行秒杀操作
     *
     * @param seckillId
     * @param userPhone
     * @param md5
     */
    @Transactional
    /**
     * 使用注解控制事务方法的优点：
     * 1、开发团队达成一致约定，明确标注事务方法的编程风格
     * 2、保证事务方法的执行时间尽可能短，不要穿插其它网络操作，RFC/HTTP请求剥离到事务方法外部
     * 3、不是所有方法都需要事务，如只有一条修改操作，只读操作不需要事务控制
     */
    @Override
    public SeckillExecution excuteSeckill(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillException {
        if(md5==null){
            throw new SeckillCloseException("没有拿到md5");
        }
        if(!md5.equalsIgnoreCase(getMD5(seckillId))){
            throw new SeckillCloseException("seckill data rewrite");
        }
        //执行秒杀逻辑：减库存+记录购买行为
        Date nowTime = new Date();
        try {

            //否则更新了库存，秒杀成功,增加明细
            int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
            //看是否该明细被重复插入，即用户是否重复秒杀
            if (insertCount <= 0) {
                throw new RepeatKillException("seckill repeated");
            } else {

                //减库存,热点商品竞争
                int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
                if (updateCount <= 0) {
                    //没有更新库存记录，说明秒杀结束 rollback
                    throw new SeckillCloseException("seckill is closed");
                } else {
                    //秒杀成功,得到成功插入的明细记录,并返回成功秒杀的信息 commit
                    SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
                }

            }
        }catch (SeckillCloseException e1){
            throw e1;
        }catch (RepeatKillException e2){
            throw e2;
        } catch (Exception e){
            //所有编译期异常转化为运行期异常
            throw new SeckillException("seckill inner error"+e.getMessage());
        }
    }
}
