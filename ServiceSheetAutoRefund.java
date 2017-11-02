package com.goodbaby.workflow.servicesheet.job;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.quartz.JobExecutionContext;
import org.quartz.StatefulJob;
import org.springframework.beans.factory.annotation.Autowired;

import com.goodbaby.core.scheduler.HibernateAwareJob;
import com.goodbaby.message.service.MessageService;
import com.goodbaby.workflow.order_market.model.CrmOrderInfo;
import com.goodbaby.workflow.order_market.model.CrmOrderTaobaoOptHistory;
import com.goodbaby.workflow.order_market.model.CrmRefundOrder;
import com.goodbaby.workflow.order_market.service.CrmOrderService;
import com.goodbaby.workflow.servicesheet.model.CrmNewServiceSheet;
import com.goodbaby.workflow.servicesheet.model.CrmNewServiceSheetExtra;
import com.goodbaby.workflow.servicesheet.service.CrmServiceSheetService;

public class ServiceSheetAutoRefund extends HibernateAwareJob implements StatefulJob {
	private static long tamp;
	private static Properties p = null;
	private static String whileListOfAg;
	@Autowired
	private CrmOrderService crmOrderService;
	static {
		p = new Properties();
		try {
			p.load(new FileInputStream(new File(
					ServiceSheetAutoRefund.class.getClassLoader().getResource("global.properties").getPath())));
			whileListOfAg = p.getProperty("ag.order.source.type.id");
		} catch (Exception e) {

			e.printStackTrace();
		}
	}

	public CrmOrderService getCrmOrderService() {
		return crmOrderService;
	}

	public void setCrmOrderService(CrmOrderService crmOrderService) {
		this.crmOrderService = crmOrderService;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void doExecute(JobExecutionContext jobexecutioncontext) {

		String flagsql = "";
		String sql1 = "from  CrmNewServiceSheet a  where a.relatedSituation like '90%' and a.serviceSheetStatusId=904003 and a.groupId=826  and a.serviceSheetId >"
				+ tamp + " and a.ccUserId=4 order by a.serviceSheetId Asc";
		List<CrmNewServiceSheet> sheetList = _crmServiceSheetService.queryHQLForSheet(sql1, 100);
		if (sheetList.size() == 0) {
			// 如果发现没有工单可处理，就从头再来一次，保证其他的处理失败的单子再次尝试
			tamp = 0;
		}
		for (int j = 0; j < sheetList.size(); j++) {
			CrmNewServiceSheet crmNewServiceSheet = sheetList.get(j);
			if (j == sheetList.size() - 1) {
				tamp = crmNewServiceSheet.getServiceSheetId();
			}
			try {
				
				  //单独的一个单子测试用代码
				/*if(crmNewServiceSheet.getServiceSheetId()!=2194012){
				 continue; }*/
				 
				// 2.根据查询出来的serviceSheet查询CrmOrderInfo 对象
				CrmOrderInfo orderInfo = _crmServiceSheetService
						.getcrmOrderInfoByserviceSheetId(crmNewServiceSheet.getRelatedSituation());
				if (orderInfo == null || !orderInfo.getOrderSn().startsWith("90"))
					continue;
				CrmOrderInfo parentOrderInfo = crmOrderService.getCrmOrderInfoByOrderSn(orderInfo.getParentSn());
				if (parentOrderInfo == null || !parentOrderInfo.getOrderSn().startsWith("90")) {
					continue;
				}
				Long orderSourceTypeId = orderInfo.getOrderSourceTypeId();
				String[] split = whileListOfAg.split(",");
				List<String> orderSourceList = Arrays.asList(split);
				if (!orderSourceList.contains(orderSourceTypeId + "")) {
					continue;
				}
				CrmNewServiceSheetExtra extra = _crmServiceSheetService
						.getCrmServiceSheetExtra(crmNewServiceSheet.getServiceSheetId());
				// 获取退款方式
				String refundWay = extra.getRefundWay();
				String OrderSn = orderInfo.getOrderSn();
				String relate = crmNewServiceSheet.getRelatedSituation();
				/*
				 * 中台新系统Ag退款分析：
				 * 从工单出发，找出工单所关联的90单号，分析此关联的90单号是取消单，还是退货单，根据from_type 和 so_sign
				 * 来判断 1.取消发货，from_type=10，so_sign=2,自动取消单生成退款工单
				 * 2.退货入仓,from_type=10,so_sign=1,6,7,退货确认生成退款工单
				 * sosign的获取方式，可以根据90单号，在crm_order_push
				 * 表里面获取此so_sign的值，如果没有就重新抓取一下订单，如果有的话，就直接取值
				 * 
				 */
				// 根据order_id在CRMOrderPush表中获取sosign
				Long sosign = _crmServiceSheetService.getZtSosignByOrderId(orderInfo.getOrderId());
				CrmOrderTaobaoOptHistory optHistory = null;
				CrmRefundOrder refundOrder = null;
				if (orderInfo.getOrderTypeId() == 75802L && "0".equals(refundWay)
						&& new Long(10).equals(orderInfo.getFromType())
						&& (new Long(1).equals(sosign) || new Long(7).equals(sosign) || new Long(6).equals(sosign))) {
					//退货入仓
					String refundId = orderInfo.getTaoBaoIds();
					if (StringUtils.isEmpty(refundId)) {
						refundId = crmOrderService.getRefundIdByOrderId(orderInfo.getOrderSn());
					}
					if (StringUtils.isEmpty(refundId))
						continue;
					this.getLog().info("工单[" + crmNewServiceSheet.getServiceSheetId() + "] 退货入仓,Ag自动退款开始, "+"退款单号："+refundId);
					Map<String, Object> map = new HashMap<String, Object>();
					
					map.put("crmOrderInfo", orderInfo);
					map.put("crmNewServiceSheet", crmNewServiceSheet);
					map.put("warehouseStatus", 1L);
					map.put("refundId", refundId);
					_crmServiceSheetService.transferTaobaoApiToRefund(orderSourceTypeId, map);
					this.getLog().info("工单[" + crmNewServiceSheet.getServiceSheetId() + "] 退货入仓,Ag自动退款处理完成, "+"退款单号："+refundId);
					
				} else {
					//取消发货
					if (orderInfo.getOrderTypeId() == 75801L && "0".equals(refundWay)
							&& new Long(10).equals(orderInfo.getFromType()) && new Long(2).equals(sosign)) {
						String refundId = orderInfo.getTaoBaoIds();
						if (StringUtils.isEmpty(refundId)) {
							refundId = crmOrderService.getRefundIdByOrderId(orderInfo.getOrderSn());
						}
						if (StringUtils.isEmpty(refundId)) {
							continue;
						}
						String taoBaoIds = parentOrderInfo.getTaoBaoIds();
						if (StringUtils.isEmpty(taoBaoIds)) {
							continue;
						}
						this.getLog().info("工单[" + crmNewServiceSheet.getServiceSheetId() + "] 取消发货,Ag自动退款开始,淘宝单号: "+taoBaoIds+"退款单号："+refundId);
						Map<String, Object> map = new HashMap<String, Object>();
						map.put("crmOrderInfo", orderInfo);
						map.put("crmNewServiceSheet", crmNewServiceSheet);
						map.put("tid", taoBaoIds);
						map.put("refundIds", refundId);
						_crmServiceSheetService.transferTaobaoApiToCancel(orderSourceTypeId, map);
						this.getLog().info("工单[" + crmNewServiceSheet.getServiceSheetId() + "] 取消发货,Ag自动退款处理完成,淘宝单号: "+taoBaoIds+"退款单号："+refundId);
						
					}
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				this.getLog().error("工单[" + crmNewServiceSheet.getServiceSheetId() + "]调用淘宝接口退款，保存回执异常：", e);
			}
		}

	}

	public void setCrmServiceSheetService(CrmServiceSheetService crmServiceSheetService) {
		_crmServiceSheetService = crmServiceSheetService;

	}

	public void setMessageService(MessageService messageService) {
		_messageService = messageService;
	}

	private CrmServiceSheetService _crmServiceSheetService;

	private MessageService _messageService;

}
