`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// 实验一：32 位 LED 跑马灯
//
// 端口：
//   clk    : 100 MHz 系统时钟
//   rst_n  : 异步复位，低电平有效
//   led    : 32 位 LED 输出，低电平点亮
//
// 功能：
//   1. rst_n 为低时异步复位，仅 LED1 点亮（led = 32'hFFFF_FFFE）；
//   2. rst_n 拉高后，每 1 秒循环移位一次（{led[30:0], led[31]}）。
//////////////////////////////////////////////////////////////////////////////////

module led_flow
(
    input  wire        clk,     // 100 MHz 时钟
    input  wire        rst_n,   // 异步复位，低电平有效
    output reg  [31:0] led      // 32 位 LED 输出，低电平点亮
);

    // 100 MHz 时钟下，计数 100_000_000 次为 1 秒
    parameter CNT_MAX = 100_000_000;

    reg [26:0] counter;
    wire       cnt_equ;

    assign cnt_equ = (counter == CNT_MAX - 1);

    // 1 秒定时计数器
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            counter <= 27'd0;
        else if (cnt_equ)
            counter <= 27'd0;
        else
            counter <= counter + 1'b1;
    end

    // LED 循环移位
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            led <= 32'hFFFF_FFFE;   // 复位时仅 LED1 亮（低电平有效）
        else if (cnt_equ)
            led <= {led[30:0], led[31]};
    end

endmodule
