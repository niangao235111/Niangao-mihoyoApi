package com.niangao.mihoyo.modules.genshin.util;

/** 国服战绩用 region 区分官服/B服；兼容旧版本站的 B 服别名。 */
public final class GenshinChannels {
    private GenshinChannels() {}
    public static boolean supports(String biz, String region) {
        return "hk4e_cn".equals(biz) && ("cn_gf01".equals(region) || "cn_qd01".equals(region))
                || "hk4e_bilibili".equals(biz) && "cn_qd01".equals(region);
    }
}
