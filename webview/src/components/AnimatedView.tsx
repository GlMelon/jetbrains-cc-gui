import type { ReactNode } from 'react';
import { motion } from 'motion/react';

interface AnimatedViewProps {
  children: ReactNode;
}

/**
 * AnimatedView — 视图切换动画壳 + 高度链传递层。
 *
 * 取代 App.tsx 中三处重复的 motion.div。关键改动:用 `flex:1`(撑满 #app 中
 * ChatHeader 之外的剩余高度)+ `flex column`,把 #app 的确定高度沿 flex 链
 * 向下传递,使视图内部的 `flex:1` 区域(如 .messages-shell)得以撑满,
 * 不再因「非 flex 包裹层断链」而塌缩。
 *
 * 调用方须用 React key 标识视图(如 <AnimatedView key="chat">),AnimatePresence
 * 据此触发 exit→enter。高度链契约见 base.less 顶部;视图自身三段式布局由各
 * 视图根负责,本组件只承担「动画 + 高度传递」,不干预子内容布局。
 */
export const AnimatedView = ({ children }: AnimatedViewProps) => (
  <motion.div
    initial={{ opacity: 0, y: 8 }}
    animate={{ opacity: 1, y: 0 }}
    exit={{ opacity: 0, y: -8 }}
    transition={{ duration: 0.2, ease: 'easeOut' }}
    style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}
  >
    {children}
  </motion.div>
);

export default AnimatedView;
